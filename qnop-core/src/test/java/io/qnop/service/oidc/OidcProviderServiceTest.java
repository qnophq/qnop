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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.OidcProvider;
import io.qnop.repository.OidcProviderRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class OidcProviderServiceTest {

  @Mock private OidcProviderRepository providers;
  @Mock private ApplicationEventPublisher events;

  private OidcProviderService service;

  @BeforeEach
  void setUp() {
    // Use the real SSRF policy so its rules are exercised through the service.
    service = new OidcProviderService(providers, new OidcSsrfPolicy(false), events);
  }

  @Test
  @DisplayName("create stores a disabled provider, defaults the scope, and never echoes the secret")
  void createMasksSecret() {
    when(providers.save(any(OidcProvider.class))).thenAnswer(inv -> inv.getArgument(0));

    OidcProviderView view =
        service.create(
            "Google",
            "GOOGLE",
            "client-123",
            "super-secret",
            "https://accounts.google.com",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(view.enabled()).isFalse();
    assertThat(view.providerType()).isEqualTo("GOOGLE");
    assertThat(view.scope()).isEqualTo("openid email profile");
    assertThat(view.hasClientSecret()).isTrue();
    // The record has no field that could carry the plaintext secret.
    assertThat(view.toString()).doesNotContain("super-secret");
  }

  @Test
  @DisplayName("create rejects a blocked issuer URI before saving (SSRF)")
  void createRejectsBlockedIssuer() {
    assertThatThrownBy(
            () ->
                service.create(
                    "Internal",
                    "OIDC",
                    "cid",
                    "secret",
                    "http://169.254.169.254/",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(providers, never()).save(any());
  }

  @Test
  @DisplayName("delete throws for an unknown provider")
  void deleteUnknown() {
    UUID id = UUID.randomUUID();
    when(providers.existsById(id)).thenReturn(false);

    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(OidcProviderNotFoundException.class);
  }
}
