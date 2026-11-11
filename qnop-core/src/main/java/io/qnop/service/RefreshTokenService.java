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

import io.qnop.entity.RefreshToken;
import io.qnop.entity.User;
import io.qnop.repository.RefreshTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.security.QnopProperties;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, rotates, and revokes the opaque refresh tokens that back the HttpOnly session cookie
 * (issue #17). Only the {@link RefreshTokenHasher HMAC} of each token is stored. Rotation is
 * single-use with family-wide reuse detection:
 *
 * <ul>
 *   <li>presenting an <em>active</em> token revokes it ({@code ROTATED}) and issues a successor in
 *       the same family;
 *   <li>presenting an already-<em>revoked</em> token (a replay) revokes the entire family ({@code
 *       REUSE_DETECTED}) — the standard refresh-token reuse response;
 *   <li>an unknown or expired token yields {@link RotationResult.Unknown}, indistinguishable from
 *       reuse to the caller.
 * </ul>
 *
 * <p>Rotation is atomic (issue #902): the presented row is locked before it is checked, so two
 * concurrent rotations of one token are serialised — the first yields the only successor, the
 * second finds the token revoked and is treated as reuse. The owning user row is share-locked first
 * (always user before token, the order every revocation path writes in), and a token issued before
 * the user's sessions were invalidated — or whose user is gone or disabled — is revoked instead of
 * rotated, so an in-flight rotation can never carry a session past a revocation.
 */
@Service
public class RefreshTokenService {

  static final String ROTATED = "ROTATED";
  static final String REUSE_DETECTED = "REUSE_DETECTED";
  static final String LOGOUT = "LOGOUT";
  static final String INVALIDATED = "INVALIDATED";
  private static final int TOKEN_BYTES = 32;

  private final RefreshTokenRepository repository;
  private final UserRepository users;
  private final RefreshTokenHasher hasher;
  private final QnopProperties properties;
  private final SchedulerService scheduler;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  public RefreshTokenService(
      RefreshTokenRepository repository,
      UserRepository users,
      RefreshTokenHasher hasher,
      QnopProperties properties,
      SchedulerService scheduler) {
    this.repository = repository;
    this.users = users;
    this.hasher = hasher;
    this.properties = properties;
    this.scheduler = scheduler;
  }

  /** Issues a fresh refresh token in a new family (initial login). */
  @Transactional
  public IssuedRefreshToken issue(UUID userId) {
    return issueInFamily(userId, UUID.randomUUID()).toIssued(properties.auth().refreshTokenTtl());
  }

  /** Rotates a presented token; see the class doc for reuse-detection semantics. */
  @Transactional
  public RotationResult rotate(String presentedPlaintext) {
    String lookupHash = hasher.hash(presentedPlaintext);
    UUID ownerId = repository.findUserIdByTokenLookupHash(lookupHash).orElse(null);
    if (ownerId == null) {
      return new RotationResult.Unknown();
    }
    // Lock order user -> token, matching every revocation path (see the class doc).
    User owner = users.lockForSessionCheck(ownerId).orElse(null);
    RefreshToken row = repository.findForRotation(lookupHash).orElse(null);
    if (row == null) {
      return new RotationResult.Unknown();
    }
    Instant now = Instant.now();
    if (row.getRevokedAt() != null) {
      repository.revokeFamily(row.getFamilyId(), REUSE_DETECTED, now);
      return new RotationResult.Reused();
    }
    if (row.getExpiresAt().isBefore(now)) {
      return new RotationResult.Unknown();
    }
    if (sessionInvalidated(owner, row)) {
      repository.revokeFamily(row.getFamilyId(), INVALIDATED, now);
      return new RotationResult.Unknown();
    }
    Minted successor = issueInFamily(row.getUserId(), row.getFamilyId());
    row.setRevokedAt(now);
    row.setRevocationReason(ROTATED);
    row.setRotatedToId(successor.entity().getId());
    return new RotationResult.Success(
        row.getUserId(), successor.toIssued(properties.auth().refreshTokenTtl()));
  }

  /** Revokes the whole family behind a presented cookie (logout). Returns true if a row matched. */
  @Transactional
  public boolean revokePresentedFamily(String presentedPlaintext) {
    RefreshToken row =
        repository.findByTokenLookupHash(hasher.hash(presentedPlaintext)).orElse(null);
    if (row == null) {
      return false;
    }
    return repository.revokeFamily(row.getFamilyId(), LOGOUT, Instant.now()) > 0;
  }

  /** Revokes every active refresh token for a user (e.g. on password change). */
  @Transactional
  public void revokeAllForUser(UUID userId) {
    repository.revokeAllForUser(userId, LOGOUT, Instant.now());
  }

  /**
   * Daily off-peak purge of refresh-token rows whose absolute expiry has passed, so the table does
   * not grow unbounded (issue #43). Rotated/revoked rows are also removed once they expire — their
   * security purpose (reuse detection) is moot after expiry.
   */
  @Scheduled(cron = "0 40 3 * * *")
  @SchedulerLock(name = SchedulerJobCatalog.REFRESH_TOKEN_SWEEP, lockAtMostFor = "PT5M")
  public void sweepExpired() {
    scheduler.runScheduled(SchedulerJobCatalog.REFRESH_TOKEN_SWEEP);
  }

  /** The raw purge, run inside the scheduler gate's transaction (issue #524). */
  public void sweepExpiredOnce() {
    repository.deleteExpiredBefore(Instant.now());
  }

  /**
   * Whether the session behind {@code row} has been ended since the token was issued: the user is
   * gone or disabled, or the token predates {@code password_invalidated_before}. Catches a
   * successor that a rotation created while a revocation was in flight.
   */
  private static boolean sessionInvalidated(User owner, RefreshToken row) {
    if (owner == null || !owner.isEnabled()) {
      return true;
    }
    Instant invalidatedBefore = owner.getPasswordInvalidatedBefore();
    return invalidatedBefore != null
        && row.getIssuedAt() != null
        && row.getIssuedAt().isBefore(invalidatedBefore);
  }

  private Minted issueInFamily(UUID userId, UUID familyId) {
    String plaintext = generatePlaintext();
    Instant expiresAt = Instant.now().plus(properties.auth().refreshTokenTtl());
    RefreshToken saved =
        repository.save(new RefreshToken(familyId, userId, hasher.hash(plaintext), expiresAt));
    return new Minted(saved, plaintext);
  }

  private String generatePlaintext() {
    byte[] bytes = new byte[TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** A persisted refresh-token row paired with its plaintext (which is never stored). */
  private record Minted(RefreshToken entity, String plaintext) {

    IssuedRefreshToken toIssued(Duration ttl) {
      return new IssuedRefreshToken(plaintext, entity.getExpiresAt(), ttl);
    }
  }

  /** A newly issued refresh token: the plaintext for the cookie plus its expiry/max-age. */
  public record IssuedRefreshToken(String plaintext, Instant expiresAt, Duration maxAge) {}

  /** Outcome of a {@link #rotate} call. */
  public sealed interface RotationResult {

    /** Rotation succeeded; a new access token should be minted for {@code userId}. */
    record Success(UUID userId, IssuedRefreshToken token) implements RotationResult {}

    /** A revoked token was replayed; the whole family has been revoked. */
    record Reused() implements RotationResult {}

    /** No active token matched (unknown or expired). */
    record Unknown() implements RotationResult {}
  }
}
