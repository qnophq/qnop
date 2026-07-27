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
package io.qnop.service.review;

/**
 * One delivery channel for resolved {@link ReviewNotificationIntent}s (issue #538, ADR-0051).
 *
 * <p>The split exists so the expensive part — <em>who</em> should hear about a review event, a
 * policy grown issue by issue — is decided exactly once and every channel inherits it. A sink
 * decides only what is genuinely its own business: its prerequisites and its opt-outs.
 *
 * <p>{@link #accepts} is asked for each of a recipient's ranked candidates in turn and the first
 * accepted one is delivered, so a sink that refuses the highest-ranked intent still gets offered
 * the fallback.
 */
public interface ReviewNotificationSink {

  /** Whether this channel may deliver the intent at all — its prerequisites and opt-outs. */
  boolean accepts(ReviewNotificationIntent intent);

  /**
   * Delivers it. Best-effort: a throw is logged by the dispatcher and never reaches other sinks.
   */
  void deliver(ReviewNotificationIntent intent);
}
