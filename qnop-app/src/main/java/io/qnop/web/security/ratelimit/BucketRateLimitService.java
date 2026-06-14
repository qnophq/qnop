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
package io.qnop.web.security.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Reusable Bucket4j-backed rate limiter (issue #18, ADR-0027). Callers pass a {@code scope} label,
 * the in-scope {@code key} (IP, subject, hashed email, …) and the bucket's capacity / refill window
 * at consume time, so one service backs every rate-limit context.
 *
 * <p>Buckets are held in a Caffeine cache keyed {@code "scope:key"} so the same literal key in two
 * scopes (e.g. login vs change-password) never collides. Idle buckets expire to bound memory; the
 * cache is also size-capped against high-cardinality keys.
 */
@Component
public class BucketRateLimitService {

  /** Upper bound on how long an idle bucket lingers, independent of the caller's window. */
  private static final long MAX_BUCKET_TTL_SECONDS = 3_600;

  private static final long MAX_BUCKETS = 100_000;

  private final Caffeine<Object, Object> cacheBuilder =
      Caffeine.newBuilder()
          .expireAfterAccess(MAX_BUCKET_TTL_SECONDS, TimeUnit.SECONDS)
          .maximumSize(MAX_BUCKETS);

  private final java.util.concurrent.ConcurrentMap<String, Bucket> buckets =
      cacheBuilder.<String, Bucket>build().asMap();

  /**
   * Attempts to consume one token from the bucket identified by {@code scope + key}.
   *
   * @param scope rate-limit context label (e.g. {@code "login"}, {@code "change-password"})
   * @param key the in-scope bucket identifier (IP address, subject, …)
   * @param maxAttempts bucket capacity / greedy-refill size
   * @param windowSeconds refill window in seconds
   * @return {@link RateLimitResult.Allowed} with the remaining tokens, or {@link
   *     RateLimitResult.Rejected} with the seconds to wait before the next token refills
   */
  public RateLimitResult tryConsume(String scope, String key, int maxAttempts, long windowSeconds) {
    Bucket bucket =
        buckets.computeIfAbsent(
            scope + ":" + key, ignored -> newBucket(maxAttempts, windowSeconds));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return new RateLimitResult.Allowed(probe.getRemainingTokens());
    }
    long retryAfterSeconds = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1;
    return new RateLimitResult.Rejected(retryAfterSeconds);
  }

  private static Bucket newBucket(int maxAttempts, long windowSeconds) {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(maxAttempts)
                .refillGreedy(maxAttempts, Duration.ofSeconds(windowSeconds))
                .build())
        .build();
  }
}
