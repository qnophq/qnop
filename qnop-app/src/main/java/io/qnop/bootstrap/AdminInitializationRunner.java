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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the initial admin on first startup (issue #20). If no internal user named {@value
 * #ADMIN_USERNAME} exists, this runner creates one ({@code role = ADMIN}, {@code
 * password_change_required = true}) and surfaces a one-time credential.
 *
 * <p>The password comes from {@code QNOP_ADMIN_PASSWORD} when set (CI/smoke tests — then no forced
 * change); otherwise it is cryptographically random. A random credential is written to {@code
 * System.err} (bypassing SLF4J, so log forwarders never capture it) and to a {@code 0600} file at
 * {@value #PASSWORD_FILE}; SLF4J only records that bootstrap happened. Idempotent on later starts.
 */
@Component
public class AdminInitializationRunner implements ApplicationRunner {

  static final String ADMIN_USERNAME = "admin";
  static final String ADMIN_DEFAULT_EMAIL = "admin@qnop.local";
  static final String PASSWORD_FILE = "/tmp/qnop-admin-password.txt";
  private static final String ADMIN_PASSWORD_ENV = "QNOP_ADMIN_PASSWORD";
  private static final int GENERATED_PASSWORD_BYTES = 24;

  private static final Logger log = LoggerFactory.getLogger(AdminInitializationRunner.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserService userService;

  public AdminInitializationRunner(UserService userService) {
    this.userService = userService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (userService.internalUsernameExists(ADMIN_USERNAME)) {
      return;
    }

    String fixedPassword = blankToNull(System.getenv(ADMIN_PASSWORD_ENV));
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

  private void surfaceGeneratedPassword(String password) {
    System.err.printf(
        "%n=== qnop initial admin ===%nusername: %s%npassword: %s%n"
            + "Change it on first login. Also written to %s (0600).%n================================%n",
        ADMIN_USERNAME, password, PASSWORD_FILE);
    try {
      Path file = Path.of(PASSWORD_FILE);
      Files.writeString(file, "username=" + ADMIN_USERNAME + "\npassword=" + password + "\n");
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX filesystem: the stderr surface above is the fallback.
      }
    } catch (Exception e) {
      log.warn(
          "Could not write the initial admin password file at {}: {}",
          PASSWORD_FILE,
          e.getMessage());
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
