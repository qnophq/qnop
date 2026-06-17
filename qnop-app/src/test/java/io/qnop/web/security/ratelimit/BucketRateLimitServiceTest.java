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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the shared Bucket4j limiter — no Spring context, no Docker. */
class BucketRateLimitServiceTest {

  private final BucketRateLimitService service =
      new BucketRateLimitService(
          new RateLimitProperties(List.of(), null, null, null, null, null, 100_000L));

  @Test
  void allowsUpToCapacityThenRejects() {
    for (int i = 0; i < 3; i++) {
      assertThat(service.tryConsume("login", "1.2.3.4", 3, 60))
          .isInstanceOf(RateLimitResult.Allowed.class);
    }

    RateLimitResult result = service.tryConsume("login", "1.2.3.4", 3, 60);

    assertThat(result).isInstanceOf(RateLimitResult.Rejected.class);
    assertThat(((RateLimitResult.Rejected) result).retryAfterSeconds()).isPositive();
  }

  @Test
  void reportsRemainingTokens() {
    RateLimitResult first = service.tryConsume("login", "key", 5, 60);

    assertThat(((RateLimitResult.Allowed) first).remainingTokens()).isEqualTo(4);
  }

  @Test
  void isolatesBucketsByScope() {
    service.tryConsume("login", "same-key", 1, 60); // drains the login bucket

    // A different scope with the same literal key has its own, full bucket.
    assertThat(service.tryConsume("change-password", "same-key", 1, 60))
        .isInstanceOf(RateLimitResult.Allowed.class);
  }

  @Test
  void isolatesBucketsByKey() {
    service.tryConsume("login", "ip-a", 1, 60); // drains ip-a

    assertThat(service.tryConsume("login", "ip-b", 1, 60))
        .isInstanceOf(RateLimitResult.Allowed.class);
  }
}
