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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
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
}
