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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.EmailVerificationToken;
import io.qnop.entity.PasswordResetToken;
import io.qnop.entity.RefreshToken;
import io.qnop.entity.RevokedToken;
import io.qnop.entity.User;
import io.qnop.repository.EmailVerificationTokenRepository;
import io.qnop.repository.PasswordResetTokenRepository;
import io.qnop.repository.RefreshTokenRepository;
import io.qnop.repository.RevokedTokenRepository;
import io.qnop.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the token schema (issue #12) against a real PostgreSQL (ADR-0020): UUIDv7 generation,
 * the unique hash indexes, the custom revoke/lookup queries, the {@code ON DELETE CASCADE} FKs to
 * {@code qnop_user}, and the refresh-token rotation self-FK ({@code ON DELETE SET NULL}). Each test
 * runs in a rolled-back transaction. Extends {@link AbstractIntegrationTest} (Testcontainers).
 * Requires Docker.
 */
@Transactional
class TokenSchemaIT extends AbstractIntegrationTest {

  private static final Instant EXPIRES = Instant.now().plus(1, ChronoUnit.HOURS);

  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired RevokedTokenRepository revokedTokens;
  @Autowired EmailVerificationTokenRepository emailTokens;
  @Autowired PasswordResetTokenRepository passwordResetTokens;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager entityManager;

  @Test
  void indexesFamilyIdForActiveTokens() {
    // revokeFamily() filters on family_id WHERE revoked_at IS NULL; that hot path must be
    // backed by a partial index, not a sequential scan (issue #182).
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'refresh_token'"
                + " AND indexname = 'ix_refresh_token_active_family'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }

  private User newUser() {
    return users.saveAndFlush(
        User.internal(
            "Alice",
            "alice-" + UUID.randomUUID() + "@example.com",
            "u-" + UUID.randomUUID(),
            "hash"));
  }

  // --- refresh_token -------------------------------------------------------

  @Test
  void persistsRefreshTokenWithGeneratedUuidV7AndIssuedAt() {
    User user = newUser();

    RefreshToken saved =
        refreshTokens.saveAndFlush(
            new RefreshToken(UUID.randomUUID(), user.getId(), "lookup-1", EXPIRES));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).isEqualTo(7);
    assertThat(saved.getIssuedAt()).isNotNull();
  }

  @Test
  void enforcesRefreshTokenLookupHashUniqueness() {
    User user = newUser();
    refreshTokens.saveAndFlush(
        new RefreshToken(UUID.randomUUID(), user.getId(), "dup-hash", EXPIRES));

    assertThatThrownBy(
            () ->
                refreshTokens.saveAndFlush(
                    new RefreshToken(UUID.randomUUID(), user.getId(), "dup-hash", EXPIRES)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void revokeFamilyRevokesOnlyStillActiveRows() {
    User user = newUser();
    UUID family = UUID.randomUUID();
    RefreshToken active =
        refreshTokens.saveAndFlush(new RefreshToken(family, user.getId(), "active", EXPIRES));
    RefreshToken alreadyRevoked = new RefreshToken(family, user.getId(), "revoked", EXPIRES);
    alreadyRevoked.setRevokedAt(Instant.now().minusSeconds(60));
    alreadyRevoked.setRevocationReason("ROTATED");
    refreshTokens.saveAndFlush(alreadyRevoked);

    int affected = refreshTokens.revokeFamily(family, "REUSE_DETECTED", Instant.now());

    assertThat(affected).isEqualTo(1);
    assertThat(refreshTokens.findById(active.getId()).orElseThrow().getRevocationReason())
        .isEqualTo("REUSE_DETECTED");
    // The pre-revoked row keeps its original reason (forensics).
    assertThat(refreshTokens.findById(alreadyRevoked.getId()).orElseThrow().getRevocationReason())
        .isEqualTo("ROTATED");
  }

  @Test
  void revokeAllForUserRevokesActiveTokens() {
    User user = newUser();
    refreshTokens.saveAndFlush(new RefreshToken(UUID.randomUUID(), user.getId(), "a", EXPIRES));
    refreshTokens.saveAndFlush(new RefreshToken(UUID.randomUUID(), user.getId(), "b", EXPIRES));

    int affected = refreshTokens.revokeAllForUser(user.getId(), "LOGOUT", Instant.now());

    assertThat(affected).isEqualTo(2);
  }

  @Test
  void cascadesRefreshTokenDeletionWhenUserRemoved() {
    User user = newUser();
    RefreshToken token =
        refreshTokens.saveAndFlush(new RefreshToken(UUID.randomUUID(), user.getId(), "c", EXPIRES));

    users.deleteById(user.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(refreshTokens.findById(token.getId())).isEmpty();
  }

  @Test
  void nullsRotatedToIdWhenSuccessorDeleted() {
    User user = newUser();
    UUID family = UUID.randomUUID();
    RefreshToken successor =
        refreshTokens.saveAndFlush(new RefreshToken(family, user.getId(), "succ", EXPIRES));
    RefreshToken predecessor = new RefreshToken(family, user.getId(), "pred", EXPIRES);
    predecessor.setRotatedToId(successor.getId());
    refreshTokens.saveAndFlush(predecessor);

    refreshTokens.deleteById(successor.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(refreshTokens.findById(predecessor.getId()).orElseThrow().getRotatedToId()).isNull();
  }

  // --- revoked_token -------------------------------------------------------

  @Test
  void revokedTokenExistsByJtiAndEnforcesUniqueness() {
    User user = newUser();
    revokedTokens.saveAndFlush(new RevokedToken("jti-hash", user.getId(), EXPIRES));

    assertThat(revokedTokens.existsByJti("jti-hash")).isTrue();
    assertThat(revokedTokens.existsByJti("absent")).isFalse();
    assertThatThrownBy(
            () -> revokedTokens.saveAndFlush(new RevokedToken("jti-hash", user.getId(), EXPIRES)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void cascadesRevokedTokenWhenUserRemoved() {
    User user = newUser();
    RevokedToken token =
        revokedTokens.saveAndFlush(new RevokedToken("jti-x", user.getId(), EXPIRES));

    users.deleteById(user.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(revokedTokens.findById(token.getId())).isEmpty();
  }

  // --- email_verification_token / password_reset_token ---------------------

  @Test
  void emailVerificationReturnsOnlyUnconsumedTokensAndExposesEagerUser() {
    User user = newUser();
    emailTokens.saveAndFlush(new EmailVerificationToken(user, "ev-active", EXPIRES));
    EmailVerificationToken consumed = new EmailVerificationToken(user, "ev-consumed", EXPIRES);
    consumed.setConsumedAt(Instant.now());
    emailTokens.saveAndFlush(consumed);

    var unconsumed = emailTokens.findUnconsumedTokensForUser(user.getId());

    assertThat(unconsumed)
        .singleElement()
        .satisfies(t -> assertThat(t.getTokenHash()).isEqualTo("ev-active"));
    assertThat(emailTokens.findByTokenHash("ev-active").orElseThrow().getUser().getId())
        .isEqualTo(user.getId());
  }

  @Test
  void enforcesEmailVerificationTokenHashUniqueness() {
    User user = newUser();
    emailTokens.saveAndFlush(new EmailVerificationToken(user, "ev-dup", EXPIRES));

    assertThatThrownBy(
            () -> emailTokens.saveAndFlush(new EmailVerificationToken(user, "ev-dup", EXPIRES)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void passwordResetReturnsOnlyUnconsumedTokensAndCascades() {
    User user = newUser();
    PasswordResetToken token =
        passwordResetTokens.saveAndFlush(new PasswordResetToken(user, "pr-active", EXPIRES));
    PasswordResetToken consumed = new PasswordResetToken(user, "pr-consumed", EXPIRES);
    consumed.setConsumedAt(Instant.now());
    passwordResetTokens.saveAndFlush(consumed);

    assertThat(passwordResetTokens.findUnconsumedTokensForUser(user.getId())).hasSize(1);

    // Detach the managed tokens first: their EAGER @ManyToOne to the user would
    // otherwise trip Hibernate's transient-reference check on flush and mask the
    // DB-level ON DELETE CASCADE we want to verify.
    entityManager.clear();
    users.deleteById(user.getId());
    entityManager.flush();
    entityManager.clear();
    assertThat(passwordResetTokens.findById(token.getId())).isEmpty();
  }
}
