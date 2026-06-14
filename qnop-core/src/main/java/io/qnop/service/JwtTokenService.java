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

import io.qnop.security.QnopProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived HS256 access tokens (issue #17). The subject is the {@code qnop_user.id} UUID;
 * every token carries a unique {@code jti} so it can be individually revoked (see {@code
 * TokenRevocationService}). TTL and issuer come from {@code qnop.auth} (default 15m / {@code
 * qnop}).
 */
@Service
public class JwtTokenService {

  private final JwtEncoder jwtEncoder;
  private final QnopProperties properties;

  public JwtTokenService(JwtEncoder jwtEncoder, QnopProperties properties) {
    this.jwtEncoder = jwtEncoder;
    this.properties = properties;
  }

  /** Mints a signed access token for the given user id. */
  public String issueAccessToken(UUID userId) {
    QnopProperties.Auth auth = properties.auth();
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .id(UUID.randomUUID().toString())
            .issuer(auth.issuer())
            .issuedAt(now)
            .expiresAt(now.plus(auth.accessTokenTtl()))
            .subject(userId.toString())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
