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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.qnop.entity.RevokedToken;
import io.qnop.entity.User;
import io.qnop.repository.RevokedTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.security.QnopProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JWT revocation (issue #17): an explicit {@code jti} denylist (the {@code revoked_token} table,
 * fronted by a Caffeine cache to avoid a DB round-trip per request) plus bulk invalidation via
 * {@code qnop_user.password_invalidated_before} — any token issued before that instant is rejected.
 * The raw {@code jti} is SHA-256 hashed before it is cached or persisted, so a leak exposes only
 * opaque digests.
 */
@Service
public class TokenRevocationService {

  private static final long CACHE_MAX_SIZE = 10_000;

  private final RevokedTokenRepository revokedTokenRepository;
  private final UserRepository userRepository;
  private final Cache<String, Boolean> revokedJtiCache;

  public TokenRevocationService(
      RevokedTokenRepository revokedTokenRepository,
      UserRepository userRepository,
      QnopProperties properties) {
    this.revokedTokenRepository = revokedTokenRepository;
    this.userRepository = userRepository;
    this.revokedJtiCache =
        Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(properties.auth().accessTokenTtl())
            .build();
  }

  /** Adds a single access token's {@code jti} to the denylist until it would have expired. */
  @Transactional
  public void revokeToken(String jti, UUID userId, Instant expiresAt) {
    String jtiHash = hashJti(jti);
    if (revokedTokenRepository.existsByJti(jtiHash)) {
      return;
    }
    revokedTokenRepository.save(new RevokedToken(jtiHash, userId, expiresAt));
    revokedJtiCache.put(jtiHash, Boolean.TRUE);
  }

  /**
   * Whether a token must be rejected — either its {@code jti} is denylisted, or it was issued
   * before the user's password was last invalidated.
   */
  @Transactional(readOnly = true)
  public boolean isRevoked(String jti, String subject, Instant issuedAt) {
    String jtiHash = hashJti(jti);
    Boolean denylisted = revokedJtiCache.get(jtiHash, revokedTokenRepository::existsByJti);
    if (Boolean.TRUE.equals(denylisted)) {
      return true;
    }
    UUID userId;
    try {
      userId = UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
      return false; // non-UUID subject can't match a user; JWS verification already passed
    }
    User user = userRepository.findById(userId).orElse(null);
    if (user == null || user.getPasswordInvalidatedBefore() == null) {
      return false;
    }
    return issuedAt.isBefore(user.getPasswordInvalidatedBefore());
  }

  /**
   * Bulk-invalidates every existing token for a user by bumping {@code passwordInvalidatedBefore}.
   */
  @Transactional
  public void revokeAllForUser(UUID userId) {
    // Atomic, version-bumping UPDATE (issue #61): the revocation is always applied and cannot be
    // reverted by a concurrent stale full-entity save of the same user. A no-op if the user is
    // gone.
    userRepository.bumpPasswordInvalidatedBefore(userId, Instant.now());
  }

  /**
   * Daily off-peak purge of denylist entries whose underlying token would have expired anyway, so
   * {@code revoked_token} does not grow unbounded (issue #43). An expired token is rejected by JWS
   * {@code exp} validation regardless, so dropping its denylist row is safe.
   */
  @Scheduled(cron = "0 45 3 * * *")
  @SchedulerLock(name = "revokedTokenSweep", lockAtMostFor = "PT5M")
  @Transactional
  public void sweepExpired() {
    revokedTokenRepository.deleteExpiredBefore(Instant.now());
  }

  private static String hashJti(String jti) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(jti.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
