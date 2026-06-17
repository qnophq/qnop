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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.RevokedToken;
import io.qnop.entity.User;
import io.qnop.repository.RevokedTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.security.QnopProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link TokenRevocationService} (issue #44): the {@code jti} denylist (cache +
 * persistence, idempotent), and the {@code issuedAt < passwordInvalidatedBefore} bulk-invalidation
 * comparison. The Caffeine cache is exercised through a fresh service instance per test.
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

  private static final Instant EXPIRES_AT = Instant.now().plus(Duration.ofMinutes(15));

  @Mock private RevokedTokenRepository revokedTokens;
  @Mock private UserRepository users;

  private final UUID userId = UUID.randomUUID();
  private TokenRevocationService service;

  @BeforeEach
  void setUp() {
    service = new TokenRevocationService(revokedTokens, users, properties());
  }

  @Test
  @DisplayName("revokeToken hashes the jti, persists it, and never stores the raw claim")
  void revokeTokenPersistsHashedJti() {
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);

    service.revokeToken("raw-jti", userId, EXPIRES_AT);

    ArgumentCaptor<RevokedToken> saved = ArgumentCaptor.forClass(RevokedToken.class);
    verify(revokedTokens).save(saved.capture());
    assertThat(saved.getValue().getUserId()).isEqualTo(userId);
    assertThat(saved.getValue().getExpiresAt()).isEqualTo(EXPIRES_AT);
    // SHA-256 hex, never the raw jti.
    assertThat(saved.getValue().getJti()).hasSize(64).isNotEqualTo("raw-jti");
  }

  @Test
  @DisplayName("revokeToken is idempotent: an already-denylisted jti is not stored twice")
  void revokeTokenIsIdempotent() {
    when(revokedTokens.existsByJti(anyString())).thenReturn(true);

    service.revokeToken("raw-jti", userId, EXPIRES_AT);

    verify(revokedTokens, never()).save(any());
  }

  @Test
  @DisplayName("after revokeToken the jti is served from cache without a second DB probe")
  void revokeTokenCachesSoIsRevokedSkipsDb() {
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);

    service.revokeToken("raw-jti", userId, EXPIRES_AT);
    boolean revoked = service.isRevoked("raw-jti", userId.toString(), Instant.now());

    assertThat(revoked).isTrue();
    // existsByJti only during revokeToken; the cache short-circuits isRevoked.
    verify(revokedTokens, times(1)).existsByJti(anyString());
  }

  @Test
  @DisplayName("isRevoked returns true on a cache/DB denylist hit, before touching the user")
  void isRevokedTrueForDenylistedJti() {
    when(revokedTokens.existsByJti(anyString())).thenReturn(true);

    assertThat(service.isRevoked("raw-jti", userId.toString(), Instant.now())).isTrue();
    verifyNoInteractions(users);
  }

  @Test
  @DisplayName("isRevoked returns true when the token predates the password invalidation")
  void isRevokedTrueWhenIssuedBeforePasswordInvalidation() {
    Instant invalidatedAt = Instant.now();
    User user = mockUserInvalidatedAt(invalidatedAt);
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);
    when(users.findById(userId)).thenReturn(Optional.of(user));

    boolean revoked =
        service.isRevoked("raw-jti", userId.toString(), invalidatedAt.minusSeconds(60));

    assertThat(revoked).isTrue();
  }

  @Test
  @DisplayName("isRevoked returns false when the token was issued after the invalidation")
  void isRevokedFalseWhenIssuedAfterPasswordInvalidation() {
    Instant invalidatedAt = Instant.now();
    User user = mockUserInvalidatedAt(invalidatedAt);
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);
    when(users.findById(userId)).thenReturn(Optional.of(user));

    boolean revoked =
        service.isRevoked("raw-jti", userId.toString(), invalidatedAt.plusSeconds(60));

    assertThat(revoked).isFalse();
  }

  @Test
  @DisplayName("isRevoked returns false for a non-UUID subject without querying users")
  void isRevokedFalseForNonUuidSubject() {
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);

    assertThat(service.isRevoked("raw-jti", "not-a-uuid", Instant.now())).isFalse();
    verifyNoInteractions(users);
  }

  @Test
  @DisplayName("isRevoked returns false when the user no longer exists")
  void isRevokedFalseWhenUserMissing() {
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);
    when(users.findById(userId)).thenReturn(Optional.empty());

    assertThat(service.isRevoked("raw-jti", userId.toString(), Instant.now())).isFalse();
  }

  @Test
  @DisplayName("isRevoked returns false when the user's password was never invalidated")
  void isRevokedFalseWhenNeverInvalidated() {
    User user = mockUserInvalidatedAt(null);
    when(revokedTokens.existsByJti(anyString())).thenReturn(false);
    when(users.findById(userId)).thenReturn(Optional.of(user));

    assertThat(service.isRevoked("raw-jti", userId.toString(), Instant.now())).isFalse();
  }

  @Test
  @DisplayName("revokeAllForUser atomically bumps passwordInvalidatedBefore")
  void revokeAllForUserBumpsTimestamp() {
    service.revokeAllForUser(userId);

    verify(users).bumpPasswordInvalidatedBefore(eq(userId), any(Instant.class));
  }

  @Test
  @DisplayName("revokeAllForUser uses an atomic update, not a read-modify-write (issue #61)")
  void revokeAllForUserDoesNotReadModifyWrite() {
    service.revokeAllForUser(userId);

    verify(users, never()).findById(any());
    verify(users, never()).save(any());
  }

  private static User mockUserInvalidatedAt(Instant invalidatedAt) {
    User user = org.mockito.Mockito.mock(User.class);
    when(user.getPasswordInvalidatedBefore()).thenReturn(invalidatedAt);
    return user;
  }

  private static QnopProperties properties() {
    return new QnopProperties(
        new QnopProperties.Auth(
            "token-revocation-test-secret-0123456789",
            "token-revocation-test-enckey-0123456789",
            "0123456789abcdef0123456789abcdef",
            Duration.ofMinutes(15),
            Duration.ofDays(7),
            "qnop-test",
            null),
        new QnopProperties.Cors(List.of()));
  }
}
