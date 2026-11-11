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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.service.UserService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminInitializationRunnerTest {

  @TempDir Path tempRoot;

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    when(userService.internalUsernameExists("admin")).thenReturn(false);
  }

  private AdminInitializationRunner runner(String envPassword) {
    return new AdminInitializationRunner(userService, () -> envPassword, tempRoot);
  }

  @Test
  @DisplayName("a generated password lands in a new owner-only directory, file mode 0600")
  void generatedPasswordFileIsOwnerOnly() throws IOException {
    runner(null).run(null);

    verify(userService)
        .createAdmin(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    Path dir = onlyChild(tempRoot);
    assertThat(dir.getFileName().toString()).startsWith("qnop-bootstrap-");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        .isEqualTo("rwx------");
    Path file = dir.resolve("admin-password.txt");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        .isEqualTo("rw-------");
    assertThat(Files.readString(file)).startsWith("username=admin\npassword=");
  }

  @Test
  @DisplayName("never writes through a pre-existing path — a fresh directory every time")
  void neverReusesAnExistingPath() throws IOException {
    AdminInitializationRunner runner = runner(null);

    Path first = runner.writePasswordFile("first-secret");
    Path second = runner.writePasswordFile("second-secret");

    assertThat(first).isNotNull();
    assertThat(second).isNotNull().isNotEqualTo(first);
    assertThat(Files.readString(first)).contains("first-secret");
  }

  @Test
  @DisplayName("fails closed (no file, no exception) when the directory cannot be created")
  void failsClosedWhenUnwritable() {
    AdminInitializationRunner runner =
        new AdminInitializationRunner(userService, () -> null, tempRoot.resolve("missing/dir"));

    assertThat(runner.writePasswordFile("secret")).isNull();
  }

  @Test
  @DisplayName("a strong QNOP_ADMIN_PASSWORD is used as-is without a forced change and no file")
  void strongSuppliedPasswordIsAccepted() throws IOException {
    runner("Str0ng-Bootstrap!").run(null);

    verify(userService)
        .createAdmin("admin", "Administrator", "admin@qnop.local", "Str0ng-Bootstrap!", false);
    try (Stream<Path> children = Files.list(tempRoot)) {
      assertThat(children).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "short1!A", // too short
        "alllowercaseletters", // one class, under 20
        "MyAdminPassw0rd!", // contains the username
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1A!" // > 72
      })
  @DisplayName("a weak QNOP_ADMIN_PASSWORD stops the startup and creates no admin")
  void weakSuppliedPasswordIsRejected(String weak) {
    assertThatThrownBy(() -> runner(weak).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("QNOP_ADMIN_PASSWORD is too weak")
        .hasMessageNotContaining(weak);
    verify(userService, never())
        .createAdmin(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  @DisplayName("a long passphrase passes without mixing character classes")
  void passphraseIsAccepted() {
    assertThatCode(
            () -> AdminInitializationRunner.checkSuppliedPassword("correct horse battery staple"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("does nothing when the admin already exists")
  void idempotent() {
    when(userService.internalUsernameExists("admin")).thenReturn(true);

    runner("weak").run(null);

    verify(userService, never())
        .createAdmin(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  private static Path onlyChild(Path dir) throws IOException {
    try (Stream<Path> children = Files.list(dir)) {
      return children
          .reduce(
              (a, b) -> {
                throw new AssertionError("more than one entry in " + dir);
              })
          .orElseThrow();
    }
  }
}
