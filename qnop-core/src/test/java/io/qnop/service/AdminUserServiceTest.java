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
import io.qnop.repository.UserRepository;
import io.qnop.service.AdminUserService.AdminUserPage;
import io.qnop.service.AdminUserService.AdminUserView;
import io.qnop.service.auth.PasswordResetFlowService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for {@link AdminUserService} (issue #104): create/invite, edit guards, reset. */
class AdminUserServiceTest {

  private final UserRepository users = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final PasswordResetFlowService passwordResetFlow = mock(PasswordResetFlowService.class);
  private final AdminUserService service =
      new AdminUserService(users, passwordEncoder, passwordResetFlow);

  private void noClash() {
    when(users.existsByEmailIgnoreCaseAndSource(any(), any())).thenReturn(false);
    when(users.findByUsernameAndSource(any(), any())).thenReturn(Optional.empty());
    when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(passwordEncoder.encode(any())).thenReturn("hashed");
  }

  @Test
  @DisplayName("create with a password enables the account, forces a change and sends no email")
  void createWithPassword() {
    noClash();

    AdminUserView view =
        service.create("Alice", "alice", "Alice@Example.com", "MEMBER", "secretpw12");

    assertThat(view.role()).isEqualTo("MEMBER");
    assertThat(view.enabled()).isTrue();
    assertThat(view.email()).isEqualTo("alice@example.com");

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().isPasswordChangeRequired()).isTrue();
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
    verify(passwordResetFlow, never()).sendSetupLink(any());
  }

  @Test
  @DisplayName("create without a password provisions a placeholder and emails an invitation")
  void createInvite() {
    noClash();

    service.create("Bob", "bob", "bob@example.com", "AUDITOR", null);

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
    assertThat(saved.getValue().isEnabled()).isTrue();
    verify(passwordResetFlow).sendSetupLink(saved.getValue());
  }

  @Test
  @DisplayName("create rejects a duplicate email and username")
  void createRejectsDuplicates() {
    when(users.existsByEmailIgnoreCaseAndSource(any(), any())).thenReturn(true);
    assertThatThrownBy(() -> service.create("A", "a", "a@example.com", "MEMBER", "secretpw12"))
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("EMAIL_TAKEN");

    when(users.existsByEmailIgnoreCaseAndSource(any(), any())).thenReturn(false);
    when(users.findByUsernameAndSource(any(), any()))
        .thenReturn(Optional.of(User.internal("A", "a@example.com", "a", "h")));
    assertThatThrownBy(() -> service.create("A", "a", "b@example.com", "MEMBER", "secretpw12"))
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("USERNAME_TAKEN");
  }

  @Test
  @DisplayName("an admin cannot disable or demote their own account")
  void updateRejectsSelfLockout() {
    UUID id = UUID.randomUUID();
    User admin = User.internal("Admin", "admin@example.com", "admin", "h");
    admin.setRole(UserRole.ADMIN);
    when(users.findById(id)).thenReturn(Optional.of(admin));

    assertThatThrownBy(() -> service.update(id, null, null, false, id))
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("SELF_LOCKOUT");

    assertThatThrownBy(() -> service.update(id, null, "MEMBER", null, id))
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("SELF_LOCKOUT");
  }

  @Test
  @DisplayName("the last enabled admin cannot be demoted")
  void updateRejectsLastAdmin() {
    UUID id = UUID.randomUUID();
    User admin = User.internal("Admin", "admin@example.com", "admin", "h");
    admin.setRole(UserRole.ADMIN);
    when(users.findById(id)).thenReturn(Optional.of(admin));
    when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

    assertThatThrownBy(() -> service.update(id, null, "MEMBER", null, UUID.randomUUID()))
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("LAST_ADMIN");
  }

  @Test
  @DisplayName("update changes display name and role for a non-admin target")
  void updateAppliesChanges() {
    UUID id = UUID.randomUUID();
    User member = User.internal("Old Name", "m@example.com", "m", "h");
    member.setRole(UserRole.MEMBER);
    when(users.findById(id)).thenReturn(Optional.of(member));

    AdminUserView view = service.update(id, "New Name", "AUDITOR", null, UUID.randomUUID());

    assertThat(view.displayName()).isEqualTo("New Name");
    assertThat(view.role()).isEqualTo("AUDITOR");
    assertThat(member.getDisplayName()).isEqualTo("New Name");
  }

  @Test
  @DisplayName("get throws for an unknown user")
  void getRejectsUnknown() {
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("password reset is rejected for an external account and sent for an internal one")
  void sendPasswordReset() {
    UUID externalId = UUID.randomUUID();
    when(users.findById(externalId))
        .thenReturn(Optional.of(User.external("Ext", "ext@example.com")));
    assertThatThrownBy(() -> service.sendPasswordReset(externalId))
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("NO_LOCAL_PASSWORD");

    UUID internalId = UUID.randomUUID();
    User internal = User.internal("Int", "int@example.com", "int", "h");
    when(users.findById(internalId)).thenReturn(Optional.of(internal));
    service.sendPasswordReset(internalId);
    verify(passwordResetFlow).sendSetupLink(internal);
  }

  @Test
  @DisplayName("list lowercases and wraps the query and maps the page")
  void listMapsPage() {
    User user = User.internal("Alice", "alice@example.com", "alice", "h");
    user.setRole(UserRole.MEMBER);
    when(users.search(eq("%ali%"), eq(UserRole.MEMBER), any()))
        .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));

    AdminUserPage page = service.list(" Ali ", "MEMBER", 0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).email()).isEqualTo("alice@example.com");
  }
}
