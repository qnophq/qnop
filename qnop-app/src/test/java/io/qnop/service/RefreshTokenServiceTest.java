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
import io.qnop.repository.RefreshTokenRepository;
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

/** Unit tests for {@link RefreshTokenService} rotation and reuse-detection (DB-free). */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

  @Mock private RefreshTokenRepository repository;
  @Mock private SchedulerService scheduler;

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
    service = new RefreshTokenService(repository, hasher, properties, scheduler);
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
    when(repository.findByTokenLookupHash(any())).thenReturn(Optional.empty());

    assertThat(service.rotate("nope"))
        .isInstanceOf(RefreshTokenService.RotationResult.Unknown.class);
    verify(repository, never()).revokeFamily(any(), any(), any());
  }

  @Test
  void rotateActiveTokenIssuesSuccessor() {
    UUID userId = UUID.randomUUID();
    RefreshToken active =
        new RefreshToken(UUID.randomUUID(), userId, "hash", Instant.now().plusSeconds(3600));
    when(repository.findByTokenLookupHash(any())).thenReturn(Optional.of(active));
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
    RefreshToken revoked =
        new RefreshToken(family, UUID.randomUUID(), "hash", Instant.now().plusSeconds(3600));
    revoked.setRevokedAt(Instant.now().minusSeconds(60));
    when(repository.findByTokenLookupHash(any())).thenReturn(Optional.of(revoked));

    RefreshTokenService.RotationResult result = service.rotate("replayed");

    assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Reused.class);
    verify(repository).revokeFamily(eq(family), eq(RefreshTokenService.REUSE_DETECTED), any());
  }
}
