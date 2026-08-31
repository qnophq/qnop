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

import io.qnop.service.TokenRevocationService;
import io.qnop.spi.auth.BearerCredentialAuthenticator;
import io.qnop.spi.auth.MachinePrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * The {@link JwtDecoder} used by the resource-server filter (issue #17). It verifies the token with
 * the local HMAC decoder, then enforces revocation: a locally-issued token must carry {@code
 * jti}/{@code sub}/{@code iat}, and must not be denylisted or pre-date the user's password
 * invalidation. Missing claims fail loudly rather than silently skipping the revocation check.
 *
 * <p>External OIDC provider decoders will be layered in here as a fallback by issue #21.
 *
 * <p><strong>Machine credentials (issue #686, ADR-0060):</strong> when the local decode rejects the
 * credential, the registered {@link BearerCredentialAuthenticator}s are consulted — the seam
 * through which an add-on authenticates service-account credentials against the existing API. The
 * placement is the enforcement: the seam sits inside bearer processing, after the rate limiters,
 * with the core's standard 401 for anything unclaimed. A contributed principal is constrained here,
 * not by convention: a subject that parses as a UUID is rejected (it could impersonate a user), and
 * its scopes surface as {@code EXT_}-prefixed authorities only (see {@link
 * RoleJwtAuthenticationConverter}), so no human role gate can ever pass.
 */
@Component
public class DelegatingJwtDecoder implements JwtDecoder {

  private static final Logger log = LoggerFactory.getLogger(DelegatingJwtDecoder.class);

  /** Marker claim of a machine principal; its value is the literal kind. */
  public static final String ACTOR_KIND_CLAIM = "qnop_actor_kind";

  /** Claim carrying a machine principal's scopes (list of strings). */
  public static final String EXT_SCOPES_CLAIM = "qnop_ext_scopes";

  private final JwtDecoder localDecoder;
  private final TokenRevocationService tokenRevocationService;
  private final List<BearerCredentialAuthenticator> machineAuthenticators;

  public DelegatingJwtDecoder(
      @Qualifier("localJwtDecoder") JwtDecoder localDecoder,
      TokenRevocationService tokenRevocationService,
      List<BearerCredentialAuthenticator> machineAuthenticators) {
    this.localDecoder = localDecoder;
    this.tokenRevocationService = tokenRevocationService;
    this.machineAuthenticators = List.copyOf(machineAuthenticators);
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    Jwt jwt;
    try {
      jwt = localDecoder.decode(token); // throws JwtException when signature/expiry invalid
    } catch (JwtException notAUserToken) {
      // Not one of ours — offer it to the contributed machine authenticators (#686). Anything
      // unclaimed keeps the original rejection, so the failure shape never changes.
      Jwt machine = authenticateMachine(token);
      if (machine != null) {
        return machine;
      }
      throw notAUserToken;
    }
    String jti = jwt.getId();
    String subject = jwt.getSubject();
    Instant issuedAt = jwt.getIssuedAt();
    // BadJwtException (not a plain JwtException) so the resource server maps these to a 401
    // (InvalidBearerTokenException) rather than a 500 (AuthenticationServiceException).
    if (jti == null || subject == null || issuedAt == null) {
      throw new BadJwtException("Token missing a required claim (jti/sub/iat)");
    }
    if (tokenRevocationService.isRevoked(jti, subject, issuedAt)) {
      throw new BadJwtException("Token has been revoked");
    }
    return jwt;
  }

  private Jwt authenticateMachine(String token) {
    if (machineAuthenticators.isEmpty()) {
      return null;
    }
    for (BearerCredentialAuthenticator authenticator : machineAuthenticators) {
      Optional<MachinePrincipal> principal;
      try {
        principal = authenticator.authenticate(token);
      } catch (RuntimeException e) {
        // A broken add-on must not turn a bad credential into a 500 — it just doesn't claim it.
        log.warn(
            "machine authenticator {} failed — treating credential as unclaimed",
            authenticator.getClass().getName(),
            e);
        continue;
      }
      if (principal.isEmpty()) {
        continue;
      }
      return toJwt(token, principal.get());
    }
    return null;
  }

  /**
   * Renders a machine principal as a synthetic {@link Jwt} so it flows through the existing
   * resource-server pipeline (converter, entry point, {@code PasswordChangeRequiredFilter} — which
   * ignores it for lack of a {@code pcr} claim). The UUID-subject rejection is the impersonation
   * guard: user subjects are UUIDs, so no contributed principal can ever resolve as a user.
   */
  private Jwt toJwt(String token, MachinePrincipal principal) {
    String subject = principal.subject();
    if (subject == null || subject.isBlank()) {
      throw new BadJwtException("machine principal without a subject");
    }
    if (parsesAsUuid(subject)) {
      throw new BadJwtException("machine principal subject must not be a UUID");
    }
    Instant now = Instant.now();
    return Jwt.withTokenValue(token)
        .header("alg", "none")
        .subject(subject)
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60)) // validity is re-checked per request by the authenticator
        .claim(ACTOR_KIND_CLAIM, "machine")
        .claim(EXT_SCOPES_CLAIM, List.copyOf(principal.scopes()))
        .claims(claims -> claims.putAll(Map.of()))
        .build();
  }

  private static boolean parsesAsUuid(String subject) {
    try {
      UUID.fromString(subject);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
