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
package io.qnop.spi.event;

/**
 * Subscribes to the published review-event stream (issue #685, ADR-0059).
 *
 * <p>Called after the originating transaction has committed, on a dispatcher thread — never on the
 * request thread, and never inside the transaction: a rolled-back action is never announced, and a
 * listener can neither slow nor break the action that raised the event. Each listener is isolated
 * from the others; a thrown exception is logged and dropped. Delivery is best-effort and in-process
 * — a consumer needing durability or retries (a webhook forwarder, say) queues on its own side.
 *
 * <p>Implementations must tolerate unknown {@link PublishedEvent#type() types} (the catalogue may
 * grow in minor releases) and must be safe to call from any thread.
 */
@FunctionalInterface
public interface PublishedEventListener {

  /** Handles one published event; see the class contract for threading and isolation. */
  void on(PublishedEvent event);
}
