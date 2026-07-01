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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import io.qnop.service.UserService.UserProfileView;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit test for {@link UserService#getProfile} (issue #99). */
class UserServiceTest {

  private final UserRepository users = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final UserService service = new UserService(users, passwordEncoder);

  @Test
  @DisplayName("getProfile returns an entity-free view with role and source as names")
  void getProfileReturnsView() {
    UUID id = UUID.randomUUID();
    User user = User.internal("Ivan Admin", "ivan@example.com", "ivan", "hash");
    user.setRole(UserRole.ADMIN);
    when(users.findById(id)).thenReturn(Optional.of(user));

    UserProfileView view = service.getProfile(id);

    assertThat(view.id()).isEqualTo(user.getId());
    assertThat(view.displayName()).isEqualTo("Ivan Admin");
    assertThat(view.email()).isEqualTo("ivan@example.com");
    assertThat(view.role()).isEqualTo("ADMIN");
    assertThat(view.source()).isEqualTo("INTERNAL");
  }

  @Test
  @DisplayName("getProfile throws for an unknown user")
  void getProfileRejectsUnknownUser() {
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getProfile(id)).isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("createSelfRegistered falls back to the username when the display name is blank")
  void createSelfRegisteredFallsBackToUsername() {
    when(passwordEncoder.encode("secret")).thenReturn("hashed");
    when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User created =
        service.createSelfRegistered("jdoe", "jdoe@example.com", "secret", "  ", UserRole.MEMBER);

    assertThat(created.getDisplayName()).isEqualTo("jdoe");
    assertThat(created.getRole()).isEqualTo(UserRole.MEMBER);
    assertThat(created.isEnabled()).isFalse();
    assertThat(created.getEmail()).isEqualTo("jdoe@example.com");
  }

  @Test
  @DisplayName("createSelfRegistered tolerates a null email (normalizeEmail short-circuits)")
  void createSelfRegisteredWithNullEmail() {
    when(passwordEncoder.encode("secret")).thenReturn("hashed");
    when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User created =
        service.createSelfRegistered("jdoe", null, "secret", "Jane Doe", UserRole.MEMBER);

    assertThat(created.getEmail()).isNull();
    assertThat(created.getDisplayName()).isEqualTo("Jane Doe");
  }

  @Test
  @DisplayName("internalUserExists returns true on an email match alone, normalizing case")
  void internalUserExistsOnEmailOnly() {
    when(users.existsByEmailIgnoreCaseAndSource("bob@example.com", UserSource.INTERNAL))
        .thenReturn(true);

    // The email branch short-circuits the OR, so the username lookup is never consulted.
    assertThat(service.internalUserExists("bob", "Bob@Example.com")).isTrue();
  }

  @Test
  @DisplayName(
      "applyPasswordReset re-hashes, clears the forced-change flag and invalidates sessions")
  void applyPasswordResetInvalidatesSessions() {
    UUID id = UUID.randomUUID();
    User user = User.internal("Bob", "bob@example.com", "bob", "old-hash");
    user.setPasswordChangeRequired(true);
    when(users.findById(id)).thenReturn(Optional.of(user));
    when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

    User result = service.applyPasswordReset(id, "new-password");

    assertThat(result.getPasswordHash()).isEqualTo("new-hash");
    assertThat(result.isPasswordChangeRequired()).isFalse();
    assertThat(result.getPasswordInvalidatedBefore()).isNotNull();
  }

  @Test
  @DisplayName("applyPasswordReset throws for an unknown user")
  void applyPasswordResetRejectsUnknownUser() {
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.applyPasswordReset(id, "new-password"))
        .isInstanceOf(UserNotFoundException.class);
  }
}
