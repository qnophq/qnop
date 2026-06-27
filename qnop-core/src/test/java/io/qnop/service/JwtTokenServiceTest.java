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

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.security.JwtKeyService;
import io.qnop.security.QnopProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Unit test for {@link JwtTokenService} (issues #44/#98): a minted access token must be a valid
 * HS256 JWT whose claims match the {@code qnop.auth} configuration and carry the user's current
 * global role. Tokens are decoded with a decoder built from the same HKDF-derived signing key,
 * mirroring {@code JwtConfiguration}.
 */
class JwtTokenServiceTest {

  private static final Duration TTL = Duration.ofMinutes(15);
  private static final String ISSUER = "qnop-test";

  private final SecretKey signingKey = new JwtKeyService(properties()).deriveKey("access-token");
  private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
  private final JwtDecoder decoder =
      NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
  private final UserService userService = mock(UserService.class);
  private final JwtTokenService service = new JwtTokenService(encoder, properties(), userService);

  private UUID givenUserWithRole(UserRole role) {
    return givenUser(role, false);
  }

  private UUID givenUser(UserRole role, boolean passwordChangeRequired) {
    UUID userId = UUID.randomUUID();
    User user = User.internal("Test User", "test@example.com", "test", "hash");
    user.setRole(role);
    user.setPasswordChangeRequired(passwordChangeRequired);
    when(userService.findById(userId)).thenReturn(Optional.of(user));
    return userId;
  }

  @Test
  @DisplayName("issued token is a valid HS256 JWT carrying the expected claims")
  void issuesDecodableHs256TokenWithExpectedClaims() {
    UUID userId = givenUserWithRole(UserRole.MEMBER);

    Jwt jwt = decoder.decode(service.issueAccessToken(userId));

    assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    assertThat(jwt.getSubject()).isEqualTo(userId.toString());
    // Read iss as a raw string: Jwt#getIssuer() coerces the claim to a URL, but the
    // configured issuer is a bare label ("qnop" by default), not a URL.
    assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
    assertThat(jwt.getId()).isNotNull();
    // The jti is a random UUID; parsing it back must succeed.
    assertThat(UUID.fromString(jwt.getId())).isNotNull();
    assertThat(jwt.getIssuedAt()).isNotNull();
    assertThat(jwt.getExpiresAt()).isNotNull();
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(TTL);
    // pcr defaults to false for an ordinary account.
    assertThat(jwt.getClaimAsBoolean(JwtTokenService.PCR_CLAIM)).isFalse();
  }

  @Test
  @DisplayName("token carries the user's global role as the 'role' claim")
  void embedsUserRoleClaim() {
    UUID userId = givenUserWithRole(UserRole.ADMIN);

    Jwt jwt = decoder.decode(service.issueAccessToken(userId));

    assertThat(jwt.getClaimAsString(JwtTokenService.ROLE_CLAIM)).isEqualTo("ADMIN");
  }

  @Test
  @DisplayName("token carries the user's password-change-required flag as the 'pcr' claim")
  void embedsPasswordChangeRequiredClaim() {
    UUID userId = givenUser(UserRole.MEMBER, true);

    Jwt jwt = decoder.decode(service.issueAccessToken(userId));

    assertThat(jwt.getClaimAsBoolean(JwtTokenService.PCR_CLAIM)).isTrue();
  }

  @Test
  @DisplayName("minting fails for an unknown user")
  void rejectsUnknownUser() {
    UUID userId = UUID.randomUUID();
    when(userService.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.issueAccessToken(userId))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("each token gets a distinct jti so it can be revoked individually")
  void mintsUniqueJtiPerToken() {
    UUID userId = givenUserWithRole(UserRole.MEMBER);

    Jwt first = decoder.decode(service.issueAccessToken(userId));
    Jwt second = decoder.decode(service.issueAccessToken(userId));

    assertThat(first.getId()).isNotEqualTo(second.getId());
  }

  private static QnopProperties properties() {
    return new QnopProperties(
        new QnopProperties.Auth(
            "jwt-token-service-test-secret-0123456789",
            "jwt-token-service-test-enckey-0123456789",
            "0123456789abcdef0123456789abcdef",
            TTL,
            Duration.ofDays(7),
            ISSUER,
            null,
            null),
        new QnopProperties.Cors(List.of()));
  }
}
