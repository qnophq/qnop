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
import io.qnop.entity.WorkflowState;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How much of everything this instance holds right now (issue #962): the figures behind the product
 * gauges. Counts only — nothing in here identifies a review, a document or a person.
 *
 * <p>Every map is keyed by the lower-case name of a state. The possible keys are fixed when the
 * application is built and listed by the constants here, so a caller can publish one series per key
 * without ever seeing a state it did not expect; a state with no rows is simply absent.
 *
 * @param reviews reviews per workflow state, keyed by {@link #REVIEW_STATES}
 * @param annotations annotations per status, keyed by {@link #ANNOTATION_STATUSES}
 * @param users accounts per sign-in state, keyed by {@link #USER_STATES}
 * @param storageObjects object-store registry rows per lifecycle state, keyed by {@link
 *     #STORAGE_STATUSES}
 * @param storageBytes the bytes those rows account for, keyed likewise
 */
public record ProductStats(
    Map<String, Long> reviews,
    Map<String, Long> annotations,
    Map<String, Long> users,
    Map<String, Long> storageObjects,
    Map<String, Long> storageBytes) {

  /** The key for reviews in a workflow state the Community edition does not know (ADR-0011). */
  public static final String OTHER = "other";

  public static final String USER_ENABLED = "enabled";

  /** Self-registered and still waiting for its e-mail address to be confirmed. */
  public static final String USER_PENDING = "pending";

  public static final String USER_DISABLED = "disabled";

  /** Every {@link WorkflowState}, then {@link #OTHER}. */
  public static final List<String> REVIEW_STATES = withOther(WorkflowState.values());

  public static final List<String> ANNOTATION_STATUSES = keys(AnnotationStatus.values());
  public static final List<String> USER_STATES = List.of(USER_ENABLED, USER_PENDING, USER_DISABLED);
  public static final List<String> STORAGE_STATUSES = keys(StorageObjectStatus.values());

  public ProductStats {
    reviews = Map.copyOf(reviews);
    annotations = Map.copyOf(annotations);
    users = Map.copyOf(users);
    storageObjects = Map.copyOf(storageObjects);
    storageBytes = Map.copyOf(storageBytes);
  }

  /** The key of an enum constant: its name in lower case. */
  static String key(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static List<String> keys(Enum<?>[] values) {
    return Arrays.stream(values).map(ProductStats::key).toList();
  }

  private static List<String> withOther(Enum<?>[] values) {
    return java.util.stream.Stream.concat(keys(values).stream(), java.util.stream.Stream.of(OTHER))
        .toList();
  }
}
