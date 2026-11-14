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
package io.qnop.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.StorageObject;
import io.qnop.entity.User;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.stats.ProductStats;
import io.qnop.service.stats.ProductStatsService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The grouped counts behind the product gauges, against a real PostgreSQL (issue #962). The
 * database is shared with other tests, so every assertion is on the change this test caused.
 */
class ProductStatsIT extends AbstractIntegrationTest {

  @Autowired private ProductStatsService stats;
  @Autowired private UserRepository users;
  @Autowired private StorageObjectRepository storageObjects;

  @Test
  @DisplayName("accounts are counted by whether they can sign in, and why not")
  void countsAccountsBySignInState() {
    ProductStats before = stats.current();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User enabled = users.saveAndFlush(user("stats-on-" + suffix));
    User disabled = user("stats-off-" + suffix);
    disabled.setEnabled(false);
    disabled = users.saveAndFlush(disabled);
    try {
      ProductStats after = stats.current();

      assertThat(count(after, ProductStats.USER_ENABLED))
          .isEqualTo(count(before, ProductStats.USER_ENABLED) + 1);
      assertThat(count(after, ProductStats.USER_DISABLED))
          .isEqualTo(count(before, ProductStats.USER_DISABLED) + 1);
      assertThat(count(after, ProductStats.USER_PENDING))
          .isEqualTo(count(before, ProductStats.USER_PENDING));
    } finally {
      users.delete(enabled);
      users.delete(disabled);
    }
  }

  @Test
  @DisplayName("registry rows are counted and sized per lifecycle state")
  void countsAndSizesStorageObjects() {
    ProductStats before = stats.current();
    StorageObject pending =
        storageObjects.saveAndFlush(
            StorageObject.pending(
                "sha256/zz/stats-" + UUID.randomUUID(), "hash", "application/pdf", 1234L));
    try {
      ProductStats after = stats.current();

      assertThat(after.storageObjects().getOrDefault("pending", 0L))
          .isEqualTo(before.storageObjects().getOrDefault("pending", 0L) + 1);
      assertThat(after.storageBytes().getOrDefault("pending", 0L))
          .isEqualTo(before.storageBytes().getOrDefault("pending", 0L) + 1234L);
    } finally {
      storageObjects.delete(pending);
    }
  }

  @Test
  @DisplayName("the review and annotation counts read without error and are never negative")
  void readsReviewAndAnnotationCounts() {
    ProductStats current = stats.current();

    assertThat(ProductStats.REVIEW_STATES).containsAll(current.reviews().keySet());
    assertThat(ProductStats.ANNOTATION_STATUSES).containsAll(current.annotations().keySet());
    assertThat(current.reviews().values()).allSatisfy(count -> assertThat(count).isPositive());
    assertThat(current.annotations().values()).allSatisfy(count -> assertThat(count).isPositive());
  }

  private static User user(String username) {
    return User.internal(username, username + "@example.com", username, "not-a-real-hash");
  }

  private static long count(ProductStats stats, String state) {
    return stats.users().getOrDefault(state, 0L);
  }
}
