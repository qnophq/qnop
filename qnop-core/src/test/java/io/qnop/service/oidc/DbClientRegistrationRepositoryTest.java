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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.OidcProvider;
import io.qnop.entity.OidcProviderType;
import io.qnop.repository.OidcProviderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the SSRF re-validation in {@link DbClientRegistrationRepository#refresh()} (issue
 * #168): a stored provider whose issuer/endpoint resolves to a private/loopback/metadata host must
 * be blocked at use time and skipped, with no outbound discovery fetch attempted.
 */
class DbClientRegistrationRepositoryTest {

  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final OidcSsrfPolicy denyPrivate = new OidcSsrfPolicy(false);
  private final DbClientRegistrationRepository repository =
      new DbClientRegistrationRepository(providers, denyPrivate);

  @Test
  @DisplayName(
      "skips an enabled OIDC provider whose stored issuer targets a metadata/internal host")
  void skipsProviderWithBlockedIssuerOnRefresh() {
    OidcProvider internal = enabledOidcProvider("http://169.254.169.254/.well-known");
    when(providers.findAll()).thenReturn(List.of(internal));

    repository.refresh();

    // Blocked by the SSRF policy before any discovery fetch → not in the cache.
    assertThat(repository.findByRegistrationId(OidcRegistrationIds.of(internal.getId()))).isNull();
    assertThat(repository).isEmpty();
  }

  @Test
  @DisplayName("skips an enabled OIDC provider whose stored issuer is localhost")
  void skipsProviderWithLocalhostIssuerOnRefresh() {
    OidcProvider internal = enabledOidcProvider("https://localhost:8443/realms/internal");
    when(providers.findAll()).thenReturn(List.of(internal));

    repository.refresh();

    assertThat(repository).isEmpty();
  }

  private static OidcProvider enabledOidcProvider(String issuerUri) {
    OidcProvider provider = new OidcProvider("internal", OidcProviderType.OIDC, "client-id");
    // The id is Hibernate-assigned at persist time; set it here so OidcRegistrationIds.of works.
    ReflectionTestUtils.setField(provider, "id", UUID.randomUUID());
    provider.setEnabled(true);
    provider.setClientSecret("secret");
    provider.setIssuerUri(issuerUri);
    return provider;
  }
}
