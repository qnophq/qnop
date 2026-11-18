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
import io.qnop.entity.Annotation;
import io.qnop.entity.Document;
import io.qnop.entity.StorageObject;
import io.qnop.entity.User;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.stats.ProductStats;
import io.qnop.service.stats.ProductStatsService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The grouped counts behind the product gauges, against a real PostgreSQL (issue #962). The
 * database is shared with other tests, so every assertion is on the change this test caused.
 */
class ProductStatsIT extends AbstractIntegrationTest {

  @Autowired private ProductStatsService stats;
  @Autowired private UserRepository users;
  @Autowired private StorageObjectRepository storageObjects;
  @Autowired private DocumentRepository documents;
  @Autowired private AnnotationRepository annotations;
  @Autowired private JdbcTemplate jdbc;

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
  @DisplayName("reviews and annotations are counted by state; an unknown state is 'other'")
  void countsReviewsAndAnnotations() {
    ProductStats before = stats.current();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User owner = users.saveAndFlush(user("stats-owner-" + suffix));
    Document draft = documents.saveAndFlush(new Document(owner.getId(), "Stats draft"));
    Document custom = documents.saveAndFlush(new Document(owner.getId(), "Stats custom"));
    // A state an add-on introduced (ADR-0011): the column takes it, the Community enum does not.
    jdbc.update("UPDATE document SET workflow_state = 'LEGAL_HOLD' WHERE id = ?", custom.getId());
    Annotation open = annotations.saveAndFlush(new Annotation(draft.getId(), owner.getId()));
    try {
      ProductStats after = stats.current();

      assertThat(after.reviews().getOrDefault("draft", 0L))
          .isEqualTo(before.reviews().getOrDefault("draft", 0L) + 1);
      assertThat(after.reviews().getOrDefault(ProductStats.OTHER, 0L))
          .isEqualTo(before.reviews().getOrDefault(ProductStats.OTHER, 0L) + 1);
      // Never under the add-on's own name: that would be a series per state somebody invents.
      assertThat(ProductStats.REVIEW_STATES).containsAll(after.reviews().keySet());
      assertThat(after.annotations().getOrDefault("open", 0L))
          .isEqualTo(before.annotations().getOrDefault("open", 0L) + 1);
    } finally {
      annotations.delete(open);
      documents.delete(draft);
      documents.delete(custom);
      users.delete(owner);
    }
  }

  private static User user(String username) {
    return User.internal(username, username + "@example.com", username, "not-a-real-hash");
  }

  private static long count(ProductStats stats, String state) {
    return stats.users().getOrDefault(state, 0L);
  }
}
