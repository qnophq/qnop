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
package io.qnop.service.auth;

import io.qnop.entity.EmailVerificationToken;
import io.qnop.entity.User;
import io.qnop.repository.EmailVerificationTokenRepository;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes single-use email-verification tokens (issue #20). A token is 32 random bytes,
 * surfaced once to the user (in the verification email) and stored only as its SHA-256 hex digest
 * (issue #12), so a DB leak cannot reconstruct a usable link. Tokens live 24h and are single-use.
 * Each token carries the credentials of the registration it was issued for (issue #900); several
 * registrations of one pending address hold separate, independent tokens until one is verified. A
 * daily scheduled sweep purges expired rows.
 */
@Service
public class EmailVerificationTokenService {

  static final int TOKEN_BYTES = 32;
  static final Duration TOKEN_TTL = Duration.ofHours(24);

  /** Verification links one pending address may have outstanding at once (#900). */
  public static final int MAX_OUTSTANDING_TOKENS = 3;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final EmailVerificationTokenRepository tokens;
  private final SchedulerService scheduler;

  public EmailVerificationTokenService(
      EmailVerificationTokenRepository tokens, SchedulerService scheduler) {
    this.tokens = tokens;
    this.scheduler = scheduler;
  }

  /**
   * Issues a token for one registration of {@code user}'s pending address, carrying the credentials
   * that registration asked for (issue #900). Other outstanding tokens stay valid, up to {@value
   * #MAX_OUTSTANDING_TOKENS}: the oldest beyond that are spent with one targeted update. The caller
   * holds the lock on the account row (see {@code UserService#lockForUpdate}), which makes this cap
   * exact under concurrent registrations.
   */
  @Transactional
  public IssuedToken issueForRegistration(
      User user, String username, String displayName, String passwordHash) {
    Instant now = Instant.now();
    List<UUID> outstanding = tokens.findUnconsumedIdsForUserOldestFirst(user.getId());
    int surplus = outstanding.size() - (MAX_OUTSTANDING_TOKENS - 1);
    if (surplus > 0) {
      tokens.consumeAllById(outstanding.subList(0, surplus), now);
    }
    String rawToken = generateRawToken();
    Instant expiresAt = now.plus(TOKEN_TTL);
    tokens.save(
        new EmailVerificationToken(
            user, sha256Hex(rawToken), expiresAt, username, displayName, passwordHash));
    return new IssuedToken(rawToken, expiresAt);
  }

  /**
   * Whether the user's newest verification link is younger than {@code window} — the per-address
   * issuance throttle (issue #900): within it a further registration issues no link and sends no
   * mail, so displacing a link through the cap takes at least {@code MAX_OUTSTANDING_TOKENS}
   * windows.
   */
  @Transactional(readOnly = true)
  public boolean issuedWithin(UUID userId, Duration window) {
    return tokens
        .findLatestIssuedAt(userId)
        .map(issued -> issued.isAfter(Instant.now().minus(window)))
        .orElse(false);
  }

  /**
   * Validates a token WITHOUT consuming it (issue #900) and returns what verifying it would apply:
   * the account and the registration's credentials — the account row's own for a token issued
   * before candidates were stored. Throws if unknown/expired/used.
   */
  @Transactional(readOnly = true)
  public PendingVerification peek(String rawToken) {
    EmailVerificationToken token = requireUsable(rawToken);
    User user = token.getUser();
    boolean legacy = token.getCandidatePasswordHash() == null;
    return new PendingVerification(
        token.getId(),
        user.getId(),
        user.isEmailVerificationPending(),
        legacy ? user.getUsername() : token.getCandidateUsername(),
        legacy ? user.getDisplayName() : token.getCandidateDisplayName(),
        legacy ? user.getPasswordHash() : token.getCandidatePasswordHash());
  }

  /** Consumes the token atomically (issue #61); throws if another request consumed it first. */
  @Transactional
  public void consume(UUID tokenId) {
    if (tokens.markConsumed(tokenId, Instant.now()) == 0) {
      throw new InvalidVerificationTokenException("Verification token already used");
    }
  }

  /** Spends every outstanding token of the user — once one of them has been verified (#900). */
  @Transactional
  public void consumeAllForUser(UUID userId) {
    tokens.consumeAllForUser(userId, Instant.now());
  }

  /**
   * Daily off-peak purge of expired rows so the table does not grow unbounded. Routed through the
   * scheduler gate (issue #524) so an admin can disable it or trigger a run-now; the gate owns the
   * transaction around {@link #sweepOnce()}.
   */
  @Scheduled(cron = "0 30 3 * * *")
  @SchedulerLock(name = SchedulerJobCatalog.EMAIL_VERIFICATION_TOKEN_SWEEP, lockAtMostFor = "PT5M")
  public void sweep() {
    scheduler.runScheduled(SchedulerJobCatalog.EMAIL_VERIFICATION_TOKEN_SWEEP);
  }

  /** The raw purge, run inside the scheduler gate's transaction (issue #524). */
  public void sweepOnce() {
    tokens.deleteByExpiresAtBefore(Instant.now());
  }

  private EmailVerificationToken requireUsable(String rawToken) {
    EmailVerificationToken token =
        tokens
            .findByTokenHash(sha256Hex(rawToken))
            .orElseThrow(
                () ->
                    new InvalidVerificationTokenException("Unknown or invalid verification token"));
    if (token.getConsumedAt() != null) {
      throw new InvalidVerificationTokenException("Verification token already used");
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidVerificationTokenException("Verification token has expired");
    }
    return token;
  }

  private static String generateRawToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** A freshly issued token: the raw value (emailed once) and its absolute expiry. */
  public record IssuedToken(String rawToken, Instant expiresAt) {}

  /**
   * What verifying a token would do (issue #900): which account, whether it is still pending, and
   * the credentials its registration asked for.
   */
  public record PendingVerification(
      UUID tokenId,
      UUID userId,
      boolean accountPending,
      String username,
      String displayName,
      String passwordHash) {}

  /** Raised when a presented verification token is unknown, expired, or already consumed. */
  public static class InvalidVerificationTokenException extends RuntimeException {
    public InvalidVerificationTokenException(String message) {
      super(message);
    }
  }
}
