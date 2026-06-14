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
import java.time.Instant;
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
 */
@Component
public class DelegatingJwtDecoder implements JwtDecoder {

  private final JwtDecoder localDecoder;
  private final TokenRevocationService tokenRevocationService;

  public DelegatingJwtDecoder(
      @Qualifier("localJwtDecoder") JwtDecoder localDecoder,
      TokenRevocationService tokenRevocationService) {
    this.localDecoder = localDecoder;
    this.tokenRevocationService = tokenRevocationService;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    Jwt jwt = localDecoder.decode(token); // throws JwtException when signature/expiry invalid
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
}
