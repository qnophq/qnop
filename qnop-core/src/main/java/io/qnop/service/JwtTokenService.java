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

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
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
 * TokenRevocationService}) and the user's global {@link UserRole} as a {@value #ROLE_CLAIM} claim,
 * which the resource-server filter maps to a {@code ROLE_*} authority (issue #98). The role is
 * resolved at mint time — on both login and refresh — so a role change takes effect on the next
 * token. TTL and issuer come from {@code qnop.auth} (default 15m / {@code qnop}).
 */
@Service
public class JwtTokenService {

  /**
   * Claim carrying the user's global role; mapped to {@code ROLE_<value>} by the resource server.
   */
  public static final String ROLE_CLAIM = "role";

  private final JwtEncoder jwtEncoder;
  private final QnopProperties properties;
  private final UserService userService;

  public JwtTokenService(
      JwtEncoder jwtEncoder, QnopProperties properties, UserService userService) {
    this.jwtEncoder = jwtEncoder;
    this.properties = properties;
    this.userService = userService;
  }

  /** Mints a signed access token for the given user id, embedding the user's current role. */
  public String issueAccessToken(UUID userId) {
    UserRole role =
        userService
            .findById(userId)
            .map(User::getRole)
            .orElseThrow(() -> new UserNotFoundException(userId));
    QnopProperties.Auth auth = properties.auth();
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .id(UUID.randomUUID().toString())
            .issuer(auth.issuer())
            .issuedAt(now)
            .expiresAt(now.plus(auth.accessTokenTtl()))
            .subject(userId.toString())
            .claim(ROLE_CLAIM, role.name())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
