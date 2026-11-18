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
 * Meters of re-anchoring (ADR-0009): what became of the annotations carried over to a new document
 * version. The share that is orphaned is the measure of how well anchoring holds.
 */
@Component
public class ReanchorMeters {

  public static final String ANNOTATIONS = "qnop.reanchor.annotations";

  /** Where a carried-over annotation ended up on the new version. */
  public enum Result {
    /** Found where it was. */
    PLACED,
    /** Found elsewhere. */
    MOVED,
    /** Not found; a reviewer has to re-attach it. */
    ORPHANED
  }

  private final Counter placed;
  private final Counter moved;
  private final Counter orphaned;

  public ReanchorMeters(MeterRegistry registry) {
    this.placed = counter(registry, Result.PLACED);
    this.moved = counter(registry, Result.MOVED);
    this.orphaned = counter(registry, Result.ORPHANED);
  }

  private static Counter counter(MeterRegistry registry, Result result) {
    return Counter.builder(ANNOTATIONS)
        .description("Annotations re-anchored onto a new document version, by result")
        .tag("result", MeterTags.of(result))
        .register(registry);
  }

  public void reanchored(int placedCount, int movedCount, int orphanedCount) {
    placed.increment(placedCount);
    moved.increment(movedCount);
    orphaned.increment(orphanedCount);
  }
}
