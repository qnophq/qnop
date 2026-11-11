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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.RefreshToken;
import io.qnop.entity.User;
import io.qnop.repository.RefreshTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.security.JwtKeyService;
import io.qnop.security.QnopProperties;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests for {@link RefreshTokenService} rotation and reuse-detection (DB-free). */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

  @Mock private RefreshTokenRepository repository;
  @Mock private SchedulerService scheduler;
  @Mock private UserRepository users;

  private RefreshTokenService service;

  @BeforeEach
  void setUp() {
    QnopProperties properties =
        new QnopProperties(
            new QnopProperties.Auth(
                "unit-test-jwt-secret-0123456789abcdef",
                "unit-test-encryption-key-0123456789",
                "0123456789abcdef",
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                "qnop",
                Boolean.TRUE,
                null),
            new QnopProperties.Cors(null));
    RefreshTokenHasher hasher = new RefreshTokenHasher(new JwtKeyService(properties));
    service = new RefreshTokenService(repository, users, hasher, properties, scheduler);
  }

  @Test
  void issueReturnsPlaintextAndExpiry() {
    when(repository.save(any(RefreshToken.class))).thenAnswer(call -> call.getArgument(0));

    RefreshTokenService.IssuedRefreshToken issued = service.issue(UUID.randomUUID());

    assertThat(issued.plaintext()).isNotBlank();
    assertThat(issued.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void rotateUnknownTokenReturnsUnknown() {
    when(repository.findUserIdByTokenLookupHash(any())).thenReturn(Optional.empty());

    assertThat(service.rotate("nope"))
        .isInstanceOf(RefreshTokenService.RotationResult.Unknown.class);
    verify(repository, never()).revokeFamily(any(), any(), any());
  }

  @Test
  void rotateActiveTokenIssuesSuccessor() {
    UUID userId = UUID.randomUUID();
    RefreshToken active =
        new RefreshToken(UUID.randomUUID(), userId, "hash", Instant.now().plusSeconds(3600));
    ownedBy(userId, enabledUser(null));
    when(repository.findForRotation(any())).thenReturn(Optional.of(active));
    when(repository.save(any(RefreshToken.class))).thenAnswer(call -> call.getArgument(0));

    RefreshTokenService.RotationResult result = service.rotate("present");

    assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Success.class);
    assertThat(((RefreshTokenService.RotationResult.Success) result).userId()).isEqualTo(userId);
    assertThat(active.getRevokedAt()).isNotNull();
    assertThat(active.getRevocationReason()).isEqualTo(RefreshTokenService.ROTATED);
  }

  @Test
  void rotateRevokedTokenDetectsReuseAndRevokesFamily() {
    UUID family = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    RefreshToken revoked =
        new RefreshToken(family, userId, "hash", Instant.now().plusSeconds(3600));
    revoked.setRevokedAt(Instant.now().minusSeconds(60));
    ownedBy(userId, enabledUser(null));
    when(repository.findForRotation(any())).thenReturn(Optional.of(revoked));

    RefreshTokenService.RotationResult result = service.rotate("replayed");

    assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Reused.class);
    verify(repository).revokeFamily(eq(family), eq(RefreshTokenService.REUSE_DETECTED), any());
  }

  @Test
  void rotateTokenIssuedBeforeSessionInvalidationRevokesInsteadOfRotating() {
    UUID userId = UUID.randomUUID();
    UUID family = UUID.randomUUID();
    RefreshToken stale = new RefreshToken(family, userId, "hash", Instant.now().plusSeconds(3600));
    ReflectionTestUtils.setField(stale, "issuedAt", Instant.now().minusSeconds(30));
    ownedBy(userId, enabledUser(Instant.now().minusSeconds(10)));
    when(repository.findForRotation(any())).thenReturn(Optional.of(stale));

    assertThat(service.rotate("present"))
        .isInstanceOf(RefreshTokenService.RotationResult.Unknown.class);
    verify(repository).revokeFamily(eq(family), eq(RefreshTokenService.INVALIDATED), any());
    verify(repository, never()).save(any());
  }

  @Test
  void rotateTokenIssuedAfterSessionInvalidationRotates() {
    UUID userId = UUID.randomUUID();
    RefreshToken fresh =
        new RefreshToken(UUID.randomUUID(), userId, "hash", Instant.now().plusSeconds(3600));
    ReflectionTestUtils.setField(fresh, "issuedAt", Instant.now().minusSeconds(5));
    ownedBy(userId, enabledUser(Instant.now().minusSeconds(60)));
    when(repository.findForRotation(any())).thenReturn(Optional.of(fresh));
    when(repository.save(any(RefreshToken.class))).thenAnswer(call -> call.getArgument(0));

    assertThat(service.rotate("present"))
        .isInstanceOf(RefreshTokenService.RotationResult.Success.class);
  }

  @Test
  void rotateTokenOfMissingOrDisabledUserRevokes() {
    UUID userId = UUID.randomUUID();
    RefreshToken row =
        new RefreshToken(UUID.randomUUID(), userId, "hash", Instant.now().plusSeconds(3600));
    when(repository.findUserIdByTokenLookupHash(any())).thenReturn(Optional.of(userId));
    when(users.lockForSessionCheck(userId)).thenReturn(Optional.empty());
    when(repository.findForRotation(any())).thenReturn(Optional.of(row));

    assertThat(service.rotate("present"))
        .isInstanceOf(RefreshTokenService.RotationResult.Unknown.class);

    User disabled = User.internal("D", "d@example.com", "d", "h");
    disabled.setEnabled(false);
    when(users.lockForSessionCheck(userId)).thenReturn(Optional.of(disabled));
    assertThat(service.rotate("present"))
        .isInstanceOf(RefreshTokenService.RotationResult.Unknown.class);
    verify(repository, never()).save(any());
  }

  private void ownedBy(UUID userId, User owner) {
    when(repository.findUserIdByTokenLookupHash(any())).thenReturn(Optional.of(userId));
    when(users.lockForSessionCheck(userId)).thenReturn(Optional.of(owner));
  }

  private static User enabledUser(Instant passwordInvalidatedBefore) {
    User user = User.internal("U", "u@example.com", "u", "h");
    user.setEnabled(true);
    user.setPasswordInvalidatedBefore(passwordInvalidatedBefore);
    return user;
  }
}
