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
package io.qnop.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters of the committed review events (ADR-0059): what happened in reviews, by kind, and how
 * often a registered listener failed on one.
 *
 * <p>The events are counted whether or not anything listens, so the series show review activity in
 * a deployment without add-ons as well.
 */
@Component
public class ReviewEventMeters {

  public static final String EVENTS = "qnop.review.events";
  public static final String LISTENER_FAILURES = "qnop.review.events.listener.failures";

  /** The kinds of review event. */
  public enum Type {
    PARTICIPANT_ADDED,
    ANNOTATION_CREATED,
    ANNOTATION_DECIDED,
    ANNOTATION_DISMISSED,
    COMMENT_ADDED,
    VERSION_UPLOADED,
    WORKFLOW_CHANGED,
    REVIEW_DELETED
  }

  private final MeterRegistry registry;

  public ReviewEventMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  /** A review event of this kind was committed. */
  public void committed(Type type) {
    Counter.builder(EVENTS)
        .description("Committed review events")
        .tag("type", MeterTags.of(type))
        .register(registry)
        .increment();
  }

  /** A published-event listener threw on an event of this kind. */
  public void listenerFailed(Type type) {
    Counter.builder(LISTENER_FAILURES)
        .description("Published-event listeners that failed on an event")
        .tag("type", MeterTags.of(type))
        .register(registry)
        .increment();
  }
}
