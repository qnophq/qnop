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
package io.qnop.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Nimbus encoder/decoder beans for self-issued HMAC-SHA256 (HS256) access tokens (issue #17). The
 * signing key is HKDF-derived from {@code qnop.auth.jwt-secret} via {@link JwtKeyService}
 * (ADR-0022), domain-separated under the {@code access-token} purpose.
 *
 * <p>The {@code localJwtDecoder} bean verifies tokens this server minted; it is wrapped by {@code
 * io.qnop.web.security.DelegatingJwtDecoder}, which adds revocation checks and (later, issue #21)
 * OIDC provider fallback. The resource-server filter uses the delegating decoder, not this one.
 */
@Configuration
public class JwtConfiguration {

  private final SecretKey signingKey;

  public JwtConfiguration(JwtKeyService jwtKeyService) {
    this.signingKey = jwtKeyService.deriveKey("access-token");
  }

  @Bean
  JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
  }

  @Bean
  JwtDecoder localJwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
