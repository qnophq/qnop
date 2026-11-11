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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.entity.UserSource;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.UserService.UserProfileView;
import io.qnop.service.limits.InstanceLimitProperties;
import io.qnop.service.limits.InstanceLimitService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit test for {@link UserService#getProfile} (issue #99). */
class UserServiceTest {

  private final UserRepository users = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  // The mocked repositories report every slug candidate free in both namespaces (issue #595), so
  // the base slug wins (issue #486).
  private final UserService service =
      new UserService(
          users,
          passwordEncoder,
          new UserSlugService(new SlugNamespace(users, mock(TeamRepository.class))),
          noLimits());

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
  @DisplayName("provisionExternal neutralises control characters from identity-provider claims")
  void provisionExternalNeutralisesControlCharacters() {
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

    User user = service.provisionExternal("Jane\r\nDoe\t", "jane@example.com");

    assertThat(user.getDisplayName()).isEqualTo("Jane  Doe");
  }

  @Test
  @DisplayName("provisionExternal falls back to the e-mail local part when no name is left")
  void provisionExternalFallsBackWhenTheNameIsOnlyControlCharacters() {
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

    assertThat(service.provisionExternal("\r\n\t", "jane.doe@example.com").getDisplayName())
        .isEqualTo("jane.doe");
    assertThat(service.provisionExternal(null, null).getDisplayName()).isEqualTo("user");
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
      "applyPasswordReset re-hashes and invalidates sessions through atomic updates (#61, #897)")
  void applyPasswordResetInvalidatesSessions() {
    UUID id = UUID.randomUUID();
    User fresh = User.internal("Bob", "bob@example.com", "bob", "new-hash");
    when(users.updatePasswordHash(id, "new-hash")).thenReturn(1);
    when(users.findById(id)).thenReturn(Optional.of(fresh));
    when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

    User result = service.applyPasswordReset(id, "new-password");

    // The hash write also clears password_change_required; the bump revokes access tokens.
    verify(users).updatePasswordHash(id, "new-hash");
    verify(users).bumpPasswordInvalidatedBefore(eq(id), any(Instant.class));
    verify(users, never()).save(any());
    assertThat(result).isSameAs(fresh);
  }

  @Test
  @DisplayName("applyPasswordReset throws for an unknown user")
  void applyPasswordResetRejectsUnknownUser() {
    UUID id = UUID.randomUUID();
    when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
    when(users.updatePasswordHash(id, "new-hash")).thenReturn(0);

    assertThatThrownBy(() -> service.applyPasswordReset(id, "new-password"))
        .isInstanceOf(UserNotFoundException.class);
    verify(users, never()).bumpPasswordInvalidatedBefore(any(), any());
  }

  /**
   * Quotas are off in these tests (issue #673): they cover this service's own behaviour, and
   * InstanceLimitServiceTest covers the quotas.
   */
  private static InstanceLimitService noLimits() {
    return new InstanceLimitService(
        InstanceLimitProperties.unlimited(),
        org.mockito.Mockito.mock(io.qnop.repository.UserRepository.class),
        org.mockito.Mockito.mock(io.qnop.repository.TeamRepository.class),
        org.mockito.Mockito.mock(io.qnop.repository.TeamMembershipRepository.class),
        org.mockito.Mockito.mock(io.qnop.repository.DocumentRepository.class));
  }
}
