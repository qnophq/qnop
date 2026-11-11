/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.qnop.bootstrap;

import io.qnop.service.UserService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the initial admin on first startup (issue #20). If no internal user named {@value
 * #ADMIN_USERNAME} exists, this runner creates one ({@code role = ADMIN}, {@code
 * password_change_required = true}) and surfaces a one-time credential.
 *
 * <p>The password comes from {@code QNOP_ADMIN_PASSWORD} when set (automated environments — then no
 * forced change); a value that fails {@link #checkSuppliedPassword} stops the startup (issue #905).
 * Otherwise it is cryptographically random. A random credential is written to {@code System.err}
 * (bypassing SLF4J, so log forwarders never capture it) and to {@value #PASSWORD_FILE_NAME} in a
 * freshly created, owner-only directory under the temp directory; SLF4J records only that bootstrap
 * happened and where that file is, never the password.
 *
 * <p>The file is written fail-closed (issue #905): the directory is created with a random name and
 * {@code rwx------} in one step, the file with {@code CREATE_NEW} and {@code rw-------} in one
 * step, so nothing that existed before — a planted file or a symbolic link — is ever followed or
 * reused, and there is no window in which the file is readable by others. Wherever that cannot be
 * guaranteed (a non-POSIX file system, any I/O error) no file is written and the stderr output
 * remains the only surface. Idempotent on later starts.
 */
@Component
public class AdminInitializationRunner implements ApplicationRunner {

  static final String ADMIN_USERNAME = "admin";
  static final String ADMIN_DEFAULT_EMAIL = "admin@qnop.local";
  static final String PASSWORD_FILE_NAME = "admin-password.txt";
  static final String PASSWORD_DIR_PREFIX = "qnop-bootstrap-";
  static final int MIN_SUPPLIED_PASSWORD_LENGTH = 12;

  /** BCrypt only looks at the first 72 bytes; anything longer would be silently truncated. */
  static final int MAX_SUPPLIED_PASSWORD_BYTES = 72;

  private static final String ADMIN_PASSWORD_ENV = "QNOP_ADMIN_PASSWORD";
  private static final int GENERATED_PASSWORD_BYTES = 24;

  private static final Logger log = LoggerFactory.getLogger(AdminInitializationRunner.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserService userService;
  private final Supplier<String> suppliedPassword;
  private final Path tempRoot;

  @Autowired
  public AdminInitializationRunner(UserService userService) {
    this(
        userService,
        () -> System.getenv(ADMIN_PASSWORD_ENV),
        Path.of(System.getProperty("java.io.tmpdir")));
  }

  /** For tests: the environment value and the directory the private directory is created in. */
  AdminInitializationRunner(
      UserService userService, Supplier<String> suppliedPassword, Path tempRoot) {
    this.userService = userService;
    this.suppliedPassword = suppliedPassword;
    this.tempRoot = tempRoot;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (userService.internalUsernameExists(ADMIN_USERNAME)) {
      return;
    }

    String fixedPassword = blankToNull(suppliedPassword.get());
    if (fixedPassword != null) {
      checkSuppliedPassword(fixedPassword);
    }
    String initialPassword = fixedPassword != null ? fixedPassword : generatePassword();
    boolean passwordChangeRequired = fixedPassword == null;

    userService.createAdmin(
        ADMIN_USERNAME,
        "Administrator",
        ADMIN_DEFAULT_EMAIL,
        initialPassword,
        passwordChangeRequired);

    log.info("Bootstrapped initial admin '{}' (first startup).", ADMIN_USERNAME);
    if (passwordChangeRequired) {
      surfaceGeneratedPassword(initialPassword);
    }
  }

  /**
   * Rejects a weak {@code QNOP_ADMIN_PASSWORD} (issue #905). That value is a long-lived ADMIN
   * credential with no forced change, so it must be at least {@value #MIN_SUPPLIED_PASSWORD_LENGTH}
   * characters, at most {@value #MAX_SUPPLIED_PASSWORD_BYTES} UTF-8 bytes (BCrypt's limit), not
   * contain the admin username, and either mix three character classes (lower case, upper case,
   * digits, other) or be a passphrase of 20 characters or more. Fails the startup with a message
   * that never echoes the value.
   */
  static void checkSuppliedPassword(String password) {
    String problem = weaknessOf(password);
    if (problem != null) {
      throw new IllegalStateException(
          ADMIN_PASSWORD_ENV
              + " is too weak: "
              + problem
              + ". Choose a stronger value or unset it.");
    }
  }

  private static String weaknessOf(String password) {
    if (password.length() < MIN_SUPPLIED_PASSWORD_LENGTH) {
      return "it must be at least " + MIN_SUPPLIED_PASSWORD_LENGTH + " characters long";
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_SUPPLIED_PASSWORD_BYTES) {
      return "it must be at most " + MAX_SUPPLIED_PASSWORD_BYTES + " bytes long";
    }
    if (password.toLowerCase(java.util.Locale.ROOT).contains(ADMIN_USERNAME)) {
      return "it must not contain the username";
    }
    if (characterClasses(password) < 3 && password.length() < 20) {
      return "it must mix at least three of lower case, upper case, digits and symbols"
          + " (or be 20 characters or longer)";
    }
    return null;
  }

  private static int characterClasses(String password) {
    boolean lower = password.chars().anyMatch(Character::isLowerCase);
    boolean upper = password.chars().anyMatch(Character::isUpperCase);
    boolean digit = password.chars().anyMatch(Character::isDigit);
    boolean other = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
    return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
  }

  private void surfaceGeneratedPassword(String password) {
    Path file = writePasswordFile(password);
    System.err.printf(
        "%n=== qnop initial admin ===%nusername: %s%npassword: %s%nChange it on first login.%s%n"
            + "================================%n",
        ADMIN_USERNAME, password, file == null ? "" : " Also written to " + file + " (0600).");
    if (file != null) {
      log.info("Initial admin password written to {} (owner-only).", file);
    }
  }

  /**
   * Writes the credential into a new owner-only directory, or returns {@code null} without leaving
   * anything behind when that cannot be done safely.
   */
  Path writePasswordFile(String password) {
    Path dir = null;
    try {
      dir =
          Files.createTempDirectory(
              tempRoot,
              PASSWORD_DIR_PREFIX,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      Path file = dir.resolve(PASSWORD_FILE_NAME);
      byte[] content =
          ("username=" + ADMIN_USERNAME + "\npassword=" + password + "\n")
              .getBytes(StandardCharsets.UTF_8);
      try (OutputStream out =
          Files.newOutputStream(
              Files.createFile(
                  file,
                  PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rw-------"))),
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS)) {
        out.write(content);
      }
      return file;
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      // Non-POSIX file system or any I/O problem: fail closed — stderr stays the only surface.
      log.warn("Not writing the initial admin password file: {}", e.getMessage());
      deleteQuietly(dir);
      return null;
    }
  }

  private static void deleteQuietly(Path dir) {
    if (dir == null) {
      return;
    }
    try {
      Files.deleteIfExists(dir.resolve(PASSWORD_FILE_NAME));
      Files.deleteIfExists(dir);
    } catch (IOException e) {
      log.warn("Could not remove {}: {}", dir, e.getMessage());
    }
  }

  private static String generatePassword() {
    byte[] bytes = new byte[GENERATED_PASSWORD_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
