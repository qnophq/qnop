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
package io.qnop.service.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.OidcIdentity;
import io.qnop.entity.OidcProvider;
import io.qnop.entity.User;
import io.qnop.repository.OidcIdentityRepository;
import io.qnop.service.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OidcIdentityServiceTest {

  @Mock private OidcIdentityRepository identities;
  @Mock private UserService userService;

  private OidcIdentityService service;
  private final OidcProvider provider = mock(OidcProvider.class);
  private final UUID providerId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new OidcIdentityService(identities, userService);
    when(provider.getId()).thenReturn(providerId);
  }

  @Test
  @DisplayName("an existing identity just bumps last-login and returns the linked user")
  void existingIdentity() {
    UUID userId = UUID.randomUUID();
    User user = mock(User.class);
    when(identities.findByOidcProviderIdAndSubject(providerId, "sub-1"))
        .thenReturn(Optional.of(new OidcIdentity(providerId, "sub-1", userId)));
    when(userService.bumpLastLogin(eq(userId), any())).thenReturn(user);

    User result = service.upsertOnLogin(provider, principal("sub-1", "a@example.com"));

    assertThat(result).isSameAs(user);
    verify(userService, never()).provisionExternal(any(), any());
  }

  @Test
  @DisplayName("a new identity provisions an external user and links it by (provider, subject)")
  void newIdentity() {
    UUID newUserId = UUID.randomUUID();
    User newUser = mock(User.class);
    when(newUser.getId()).thenReturn(newUserId);
    when(identities.findByOidcProviderIdAndSubject(providerId, "sub-2"))
        .thenReturn(Optional.empty());
    when(userService.provisionExternal("Jane", "jane@example.com")).thenReturn(newUser);
    when(userService.bumpLastLogin(eq(newUserId), any())).thenReturn(newUser);

    User result = service.upsertOnLogin(provider, principal("sub-2", "jane@example.com", "Jane"));

    assertThat(result).isSameAs(newUser);
    verify(identities).save(any(OidcIdentity.class));
  }

  @Test
  @DisplayName("a new identity without an email is rejected (no provisioning)")
  void newIdentityWithoutEmail() {
    when(provider.getName()).thenReturn("Google");
    when(identities.findByOidcProviderIdAndSubject(providerId, "sub-3"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.upsertOnLogin(provider, principal("sub-3", null)))
        .isInstanceOf(OidcEmailMissingException.class);
    verify(userService, never()).provisionExternal(any(), any());
  }

  private static ResolvedPrincipal principal(String subject, String email) {
    return new ResolvedPrincipal(subject, email, null, null);
  }

  private static ResolvedPrincipal principal(String subject, String email, String displayName) {
    return new ResolvedPrincipal(subject, email, displayName, null);
  }
}
