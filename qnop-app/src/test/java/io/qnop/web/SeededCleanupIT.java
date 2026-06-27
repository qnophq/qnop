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
package io.qnop.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.qnop.service.RefreshTokenService;
import io.qnop.service.TokenRevocationService;
import io.qnop.service.auth.EmailVerificationTokenService;
import io.qnop.service.auth.PasswordResetTokenService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Scheduled token-cleanup sweeps (issue #163). Each sweep deletes only rows whose {@code
 * expires_at} is in the past; an active row must survive. Rows are inserted directly so the test
 * controls the expiry, then the real {@code @Scheduled} sweep method is invoked (it is
 * {@code @Transactional}, which the cleanup delete needs). The seeded base wipes all token tables
 * (FK CASCADE) afterwards.
 */
class SeededCleanupIT extends SeededIntegrationTest {

  private static final Instant EXPIRED = Instant.now().minus(2, ChronoUnit.DAYS);
  private static final Instant ACTIVE = Instant.now().plus(2, ChronoUnit.DAYS);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private TokenRevocationService tokenRevocationService;
  @Autowired private PasswordResetTokenService passwordResetTokenService;
  @Autowired private EmailVerificationTokenService emailVerificationTokenService;

  private static String hash() {
    return (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
  }

  private int count(String table, String column, String value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
  }

  @Test
  void refreshTokenSweepDeletesOnlyExpiredRows() throws Exception {
    String expired = hash();
    String active = hash();
    insertRefreshToken(expired, EXPIRED);
    insertRefreshToken(active, ACTIVE);

    refreshTokenService.sweepExpired();

    assertEquals(0, count("refresh_token", "token_lookup_hash", expired));
    assertEquals(1, count("refresh_token", "token_lookup_hash", active));
  }

  @Test
  void revokedTokenSweepDeletesOnlyExpiredRows() throws Exception {
    String expired = hash();
    String active = hash();
    insertRevokedToken(expired, EXPIRED);
    insertRevokedToken(active, ACTIVE);

    tokenRevocationService.sweepExpired();

    assertEquals(0, count("revoked_token", "jti", expired));
    assertEquals(1, count("revoked_token", "jti", active));
  }

  @Test
  void passwordResetTokenSweepDeletesOnlyExpiredRows() throws Exception {
    String expired = hash();
    String active = hash();
    insertSingleUseToken("password_reset_token", expired, EXPIRED);
    insertSingleUseToken("password_reset_token", active, ACTIVE);

    passwordResetTokenService.sweep();

    assertEquals(0, count("password_reset_token", "token_hash", expired));
    assertEquals(1, count("password_reset_token", "token_hash", active));
  }

  @Test
  void emailVerificationTokenSweepDeletesOnlyExpiredRows() throws Exception {
    String expired = hash();
    String active = hash();
    insertSingleUseToken("email_verification_token", expired, EXPIRED);
    insertSingleUseToken("email_verification_token", active, ACTIVE);

    emailVerificationTokenService.sweep();

    assertEquals(0, count("email_verification_token", "token_hash", expired));
    assertEquals(1, count("email_verification_token", "token_hash", active));
  }

  private void insertRefreshToken(String lookupHash, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO refresh_token"
            + " (id, family_id, user_id, token_lookup_hash, issued_at, expires_at)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?)",
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        MEMBER_ID.toString(),
        lookupHash,
        Timestamp.from(EXPIRED),
        Timestamp.from(expiresAt));
  }

  private void insertRevokedToken(String jti, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO revoked_token (id, jti, user_id, expires_at, revoked_at)"
            + " VALUES (?::uuid, ?, ?::uuid, ?, ?)",
        UUID.randomUUID().toString(),
        jti,
        MEMBER_ID.toString(),
        Timestamp.from(expiresAt),
        Timestamp.from(EXPIRED));
  }

  private void insertSingleUseToken(String table, String tokenHash, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO "
            + table
            + " (id, user_id, token_hash, created_at, expires_at)"
            + " VALUES (?::uuid, ?::uuid, ?, ?, ?)",
        UUID.randomUUID().toString(),
        MEMBER_ID.toString(),
        tokenHash,
        Timestamp.from(EXPIRED),
        Timestamp.from(expiresAt));
  }
}
