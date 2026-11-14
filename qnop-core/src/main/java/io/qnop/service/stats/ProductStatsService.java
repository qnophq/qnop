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
package io.qnop.service.stats;

import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.StorageObjectStatus;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.repository.UserRepository;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the {@link ProductStats} (issue #962): four grouped counts, whatever the number of states,
 * so the cost of a reading does not grow with the catalogue of gauges built on it (ADR-0065).
 */
@Service
public class ProductStatsService {

  /** How long one reading may take before it is abandoned. */
  static final int READ_TIMEOUT_SECONDS = 5;

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final UserRepository users;
  private final StorageObjectRepository storageObjects;

  public ProductStatsService(
      DocumentRepository documents,
      AnnotationRepository annotations,
      UserRepository users,
      StorageObjectRepository storageObjects) {
    this.documents = documents;
    this.annotations = annotations;
    this.users = users;
    this.storageObjects = storageObjects;
  }

  /**
   * Two of the four reads (annotations, stored objects) scan their table: neither has an index on
   * its status, and one read a minute does not earn one. The timeout is what bounds the cost: a
   * reading that takes longer fails, and the gauges show {@code NaN} until the next one succeeds.
   */
  @Transactional(readOnly = true, timeout = READ_TIMEOUT_SECONDS)
  public ProductStats current() {
    Map<String, Long> reviews = new HashMap<>();
    for (Object[] row : documents.countGroupedByWorkflowState()) {
      // A state an add-on introduced (ADR-0011) is summed under one key, never named.
      String state = String.valueOf(row[0]).toLowerCase(Locale.ROOT);
      String key = ProductStats.REVIEW_STATES.contains(state) ? state : ProductStats.OTHER;
      reviews.merge(key, (Long) row[1], Long::sum);
    }

    Map<String, Long> annotationCounts = new HashMap<>();
    for (Object[] row : annotations.countGroupedByStatus()) {
      annotationCounts.put(ProductStats.key((AnnotationStatus) row[0]), (Long) row[1]);
    }

    Map<String, Long> userCounts = new HashMap<>();
    for (Object[] row : users.countGroupedBySignInState()) {
      userCounts.merge(userState((Boolean) row[0], (Boolean) row[1]), (Long) row[2], Long::sum);
    }

    Map<String, Long> objectCounts = new HashMap<>();
    Map<String, Long> objectBytes = new HashMap<>();
    for (Object[] row : storageObjects.countAndSizeGroupedByStatus()) {
      String status = ProductStats.key((StorageObjectStatus) row[0]);
      objectCounts.put(status, (Long) row[1]);
      objectBytes.put(status, ((Number) row[2]).longValue());
    }

    return new ProductStats(reviews, annotationCounts, userCounts, objectCounts, objectBytes);
  }

  private static String userState(boolean enabled, boolean verificationPending) {
    if (enabled) {
      return ProductStats.USER_ENABLED;
    }
    return verificationPending ? ProductStats.USER_PENDING : ProductStats.USER_DISABLED;
  }
}
