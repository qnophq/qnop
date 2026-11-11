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
package io.qnop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit test for {@link AuthService#authenticate} (issue #903): every outcome — success, wrong
 * password, unknown, disabled or password-less account — runs exactly one password comparison, so
 * the response time does not reveal whether an account exists.
 */
class AuthServiceTest {

  private static final String DUMMY_HASH = "dummy-hash";

  private final UserRepository users = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private AuthService service;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
    when(users.findByUsernameAndSource(anyString(), any())).thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCaseAndSource(anyString(), any())).thenReturn(Optional.empty());
    service =
        new AuthService(
            users,
            passwordEncoder,
            mock(TokenRevocationService.class),
            mock(RefreshTokenService.class));
  }

  @Test
  @DisplayName("valid credentials authenticate after one comparison against the stored hash")
  void validCredentials() {
    User user = existing("jane", true, "stored-hash");
    when(passwordEncoder.matches("secret", "stored-hash")).thenReturn(true);

    assertThat(service.authenticate("jane", "secret")).contains(user.getId());
    verify(passwordEncoder, times(1)).matches(anyString(), anyString());
  }

  @Test
  @DisplayName("a wrong password fails after one comparison against the stored hash")
  void wrongPassword() {
    existing("jane", true, "stored-hash");

    assertThat(service.authenticate("jane", "wrong")).isEmpty();
    verify(passwordEncoder, times(1)).matches("wrong", "stored-hash");
  }

  @Test
  @DisplayName("an unknown account still runs one comparison, against the dummy hash")
  void unknownAccount() {
    assertThat(service.authenticate("nobody", "secret")).isEmpty();
    verify(passwordEncoder, times(1)).matches("secret", DUMMY_HASH);
  }

  @Test
  @DisplayName("a disabled account runs one comparison against the dummy hash and fails")
  void disabledAccount() {
    existing("jane", false, "stored-hash");
    // Even a correct password must not authenticate a disabled account.
    when(passwordEncoder.matches("secret", DUMMY_HASH)).thenReturn(true);

    assertThat(service.authenticate("jane", "secret")).isEmpty();
    verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    verify(passwordEncoder, times(1)).matches("secret", DUMMY_HASH);
  }

  @Test
  @DisplayName("an account without a password hash runs one comparison and fails")
  void accountWithoutHash() {
    existing("jane", true, null);

    assertThat(service.authenticate("jane", "secret")).isEmpty();
    verify(passwordEncoder, times(1)).matches("secret", DUMMY_HASH);
  }

  private User existing(String username, boolean enabled, String passwordHash) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(UUID.randomUUID());
    when(user.isEnabled()).thenReturn(enabled);
    when(user.getPasswordHash()).thenReturn(passwordHash);
    when(users.findByUsernameAndSource(username, UserSource.INTERNAL))
        .thenReturn(Optional.of(user));
    return user;
  }
}
