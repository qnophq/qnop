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
package io.qnop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.RefreshToken;
import io.qnop.entity.RevokedToken;
import io.qnop.entity.User;
import io.qnop.repository.RefreshTokenRepository;
import io.qnop.repository.RevokedTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.TokenRevocationService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the scheduled cleanup of expired refresh / revoked tokens (issue #43): {@code
 * sweepExpired()} purges rows past their {@code expires_at} and leaves still-valid rows untouched.
 * Runs against a real PostgreSQL (Testcontainers); each test rolls back for isolation. Requires
 * Docker.
 */
@Transactional
class TokenCleanupIT extends AbstractIntegrationTest {

  @Autowired RefreshTokenService refreshTokenService;
  @Autowired TokenRevocationService tokenRevocationService;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired RevokedTokenRepository revokedTokens;
  @Autowired UserRepository users;

  private UUID newUser() {
    return users
        .saveAndFlush(User.external("Cleanup", "cleanup-" + UUID.randomUUID() + "@e.com"))
        .getId();
  }

  @Test
  void sweepRemovesExpiredRefreshTokensButKeepsValidOnes() {
    UUID userId = newUser();
    Instant past = Instant.now().minus(Duration.ofDays(1));
    Instant future = Instant.now().plus(Duration.ofDays(1));
    UUID expired =
        refreshTokens
            .saveAndFlush(new RefreshToken(UUID.randomUUID(), userId, "lookup-expired", past))
            .getId();
    UUID active =
        refreshTokens
            .saveAndFlush(new RefreshToken(UUID.randomUUID(), userId, "lookup-active", future))
            .getId();

    refreshTokenService.sweepExpired();

    assertThat(refreshTokens.existsById(expired)).isFalse();
    assertThat(refreshTokens.existsById(active)).isTrue();
  }

  @Test
  void sweepRemovesExpiredRevokedTokensButKeepsValidOnes() {
    UUID userId = newUser();
    Instant past = Instant.now().minus(Duration.ofDays(1));
    Instant future = Instant.now().plus(Duration.ofDays(1));
    UUID expired =
        revokedTokens.saveAndFlush(new RevokedToken("jti-expired", userId, past)).getId();
    UUID active =
        revokedTokens.saveAndFlush(new RevokedToken("jti-active", userId, future)).getId();

    tokenRevocationService.sweepExpired();

    assertThat(revokedTokens.existsById(expired)).isFalse();
    assertThat(revokedTokens.existsById(active)).isTrue();
  }
}
