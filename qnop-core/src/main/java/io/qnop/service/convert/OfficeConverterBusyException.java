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
package io.qnop.service.convert;

import java.time.Duration;

/**
 * Every conversion slot was taken and none freed up in time (issue #651).
 *
 * <p>Deliberately <em>not</em> permanent. Nothing is wrong with the document and nothing is wrong
 * with the server — it was busy for a moment. An ingest job therefore comes back under the queue's
 * backoff, and an export answers 503 with a {@code Retry-After} rather than a 500 that reads like a
 * defect.
 *
 * <p>Its own type rather than a message on {@link OfficeConversionException}, because the web layer
 * has to tell "too many at once, try again" apart from "the converter failed", and matching on text
 * is not a contract.
 */
public class OfficeConverterBusyException extends OfficeConversionException {

  /** Never zero: a {@code Retry-After: 0} invites the client straight back into the queue. */
  private static final long MINIMUM_RETRY_AFTER_SECONDS = 1;

  private final long retryAfterSeconds;

  public OfficeConverterBusyException(int limit, Duration waited) {
    super(
        "all "
            + limit
            + " conversion slots are in use and none freed up within "
            + waited.toMillis()
            + " ms");
    this.retryAfterSeconds = Math.max(MINIMUM_RETRY_AFTER_SECONDS, waited.toSeconds());
  }

  /** How long a caller is asked to hold off — the wait it just spent, as a rough guide. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
