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
package io.qnop.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.RefreshToken;
import io.qnop.entity.User;
import io.qnop.repository.RefreshTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.RefreshTokenHasher;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.RefreshTokenService.IssuedRefreshToken;
import io.qnop.service.RefreshTokenService.RotationResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link RefreshTokenService} rotation against a real PostgreSQL (issue #44,
 * Testcontainers): the single-use rotation persists the rotation link and revokes the predecessor,
 * an expired token is indistinguishable from unknown, and replaying a revoked token trips
 * family-wide reuse detection. Each test runs in a rolled-back transaction. Requires Docker.
 */
@Transactional
class RefreshTokenServiceIT extends AbstractIntegrationTest {

  @Autowired RefreshTokenService refreshTokenService;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired RefreshTokenHasher hasher;
  @Autowired UserRepository users;

  private User newUser() {
    return users.saveAndFlush(
        User.internal(
            "Alice",
            "alice-" + UUID.randomUUID() + "@example.com",
            "u-" + UUID.randomUUID(),
            "hash"));
  }

  @Test
  void happyPathRotationRevokesPredecessorAndLinksSuccessor() {
    User user = newUser();
    IssuedRefreshToken issued = refreshTokenService.issue(user.getId());

    RotationResult result = refreshTokenService.rotate(issued.plaintext());

    assertThat(result).isInstanceOf(RotationResult.Success.class);
    RotationResult.Success success = (RotationResult.Success) result;
    assertThat(success.userId()).isEqualTo(user.getId());

    RefreshToken predecessor =
        refreshTokens.findByTokenLookupHash(hasher.hash(issued.plaintext())).orElseThrow();
    assertThat(predecessor.getRevokedAt()).isNotNull();
    assertThat(predecessor.getRevocationReason()).isEqualTo("ROTATED");
    assertThat(predecessor.getRotatedToId()).isNotNull();

    RefreshToken successor =
        refreshTokens.findByTokenLookupHash(hasher.hash(success.token().plaintext())).orElseThrow();
    assertThat(successor.getId()).isEqualTo(predecessor.getRotatedToId());
    assertThat(successor.getFamilyId()).isEqualTo(predecessor.getFamilyId());
    assertThat(successor.getRevokedAt()).isNull();
  }

  @Test
  void rotatingAnExpiredTokenYieldsUnknown() {
    User user = newUser();
    String plaintext = "expired-" + UUID.randomUUID();
    refreshTokens.saveAndFlush(
        new RefreshToken(
            UUID.randomUUID(),
            user.getId(),
            hasher.hash(plaintext),
            Instant.now().minusSeconds(60)));

    RotationResult result = refreshTokenService.rotate(plaintext);

    assertThat(result).isInstanceOf(RotationResult.Unknown.class);
  }

  @Test
  void rotatingAnUnknownTokenYieldsUnknown() {
    RotationResult result = refreshTokenService.rotate("never-issued-" + UUID.randomUUID());

    assertThat(result).isInstanceOf(RotationResult.Unknown.class);
  }

  @Test
  void replayingARevokedTokenRevokesTheWholeFamily() {
    User user = newUser();
    IssuedRefreshToken issued = refreshTokenService.issue(user.getId());
    RotationResult first = refreshTokenService.rotate(issued.plaintext());
    assertThat(first).isInstanceOf(RotationResult.Success.class);
    RotationResult.Success success = (RotationResult.Success) first;

    // Replay the now-revoked original: reuse detection, indistinguishable from unknown.
    RotationResult replay = refreshTokenService.rotate(issued.plaintext());

    assertThat(replay).isInstanceOf(RotationResult.Reused.class);
    RefreshToken successor =
        refreshTokens.findByTokenLookupHash(hasher.hash(success.token().plaintext())).orElseThrow();
    assertThat(successor.getRevokedAt()).isNotNull();
    assertThat(successor.getRevocationReason()).isEqualTo("REUSE_DETECTED");
  }
}
