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
package io.qnop.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.service.TokenRevocationService;
import io.qnop.spi.auth.BearerCredentialAuthenticator;
import io.qnop.spi.auth.MachinePrincipal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The machine-credential seam (issue #686, ADR-0060): the decoder fallback, its impersonation
 * guards, and the converter's refusal to mint human roles — proven with the test-only authenticator
 * the seam issues require.
 */
class MachineCredentialAuthTest {

  private final JwtDecoder local = mock(JwtDecoder.class);
  private final TokenRevocationService revocation = mock(TokenRevocationService.class);
  private final RoleJwtAuthenticationConverter converter = new RoleJwtAuthenticationConverter();

  private DelegatingJwtDecoder decoder(BearerCredentialAuthenticator... authenticators) {
    when(local.decode("svc-key")).thenThrow(new BadJwtException("not a qnop token"));
    return new DelegatingJwtDecoder(local, revocation, List.of(authenticators));
  }

  /** The test-only fake contributor. */
  private static BearerCredentialAuthenticator claiming(String subject, Set<String> scopes) {
    return credential ->
        credential.equals("svc-key")
            ? Optional.of(new MachinePrincipal(subject, scopes))
            : Optional.empty();
  }

  @Test
  void aClaimedCredentialBecomesAMachinePrincipal() {
    Jwt jwt = decoder(claiming("svc:reporting", Set.of("reviews.read"))).decode("svc-key");

    assertThat(jwt.getSubject()).isEqualTo("svc:reporting");
    assertThat(jwt.getClaimAsString(DelegatingJwtDecoder.ACTOR_KIND_CLAIM)).isEqualTo("machine");
    // The user-keyed revocation store is not consulted for machine credentials.
    verifyNoInteractions(revocation);

    var authorities =
        converter.convert(jwt).getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
    assertThat(authorities).containsExactly("EXT_reviews.read");
  }

  @Test
  void anUnclaimedCredentialKeepsTheOriginalRejection() {
    assertThatThrownBy(() -> decoder(credential -> Optional.empty()).decode("svc-key"))
        .isInstanceOf(BadJwtException.class)
        .hasMessageContaining("not a qnop token");
  }

  @Test
  void aUuidSubjectIsRejectedAsImpersonation() {
    assertThatThrownBy(
            () -> decoder(claiming(UUID.randomUUID().toString(), Set.of("x"))).decode("svc-key"))
        .isInstanceOf(BadJwtException.class)
        .hasMessageContaining("must not be a UUID");
  }

  @Test
  void aSmuggledRoleClaimNeverBecomesAHumanRole() {
    // Even a scope literally named like a role surfaces only EXT_-prefixed.
    Jwt jwt = decoder(claiming("svc:evil", Set.of("ROLE_ADMIN", "ADMIN"))).decode("svc-key");
    var authorities =
        converter.convert(jwt).getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
    assertThat(authorities).containsExactlyInAnyOrder("EXT_ROLE_ADMIN", "EXT_ADMIN");
    assertThat(authorities).noneMatch(a -> a.startsWith("ROLE_"));
  }

  @Test
  void aThrowingAuthenticatorDoesNotClaimAndDoesNotBreak() {
    BearerCredentialAuthenticator broken =
        credential -> {
          throw new IllegalStateException("add-on breakage");
        };
    Jwt jwt = decoder(broken, claiming("svc:reporting", Set.of("reviews.read"))).decode("svc-key");
    assertThat(jwt.getSubject()).isEqualTo("svc:reporting");
  }

  @Test
  void aValidUserTokenNeverReachesTheAuthenticators() {
    Jwt user =
        Jwt.withTokenValue("user-token")
            .header("alg", "HS256")
            .subject(UUID.randomUUID().toString())
            .claim("jti", "j1")
            .issuedAt(java.time.Instant.now())
            .build();
    when(local.decode("user-token")).thenReturn(user);
    when(revocation.isRevoked("j1", user.getSubject(), user.getIssuedAt())).thenReturn(false);
    BearerCredentialAuthenticator authenticator = mock(BearerCredentialAuthenticator.class);

    new DelegatingJwtDecoder(local, revocation, List.of(authenticator)).decode("user-token");

    verifyNoInteractions(authenticator);
  }
}
