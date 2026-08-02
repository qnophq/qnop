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
package io.qnop.service.limits;

/**
 * This instance is already holding as much as it is allowed to (issue #673).
 *
 * <p>Not a failure and not a denial of permission: the caller was entitled to do this and the
 * request was well-formed — the deployment simply has no room. That distinction is why it carries
 * its own type and its own status (409) rather than borrowing the validation or authorization
 * paths, and why the message says which quota and how large it is. "Refused" without a number is a
 * dead end for whoever has to fix it.
 */
public class InstanceLimitExceededException extends RuntimeException {

  private final InstanceLimit limit;
  private final int maximum;

  public InstanceLimitExceededException(InstanceLimit limit, int maximum) {
    super(limit.describe(maximum));
    this.limit = limit;
    this.maximum = maximum;
  }

  public InstanceLimit limit() {
    return limit;
  }

  public int maximum() {
    return maximum;
  }

  /** The error code published to clients, e.g. {@code USER_LIMIT_EXCEEDED}. */
  public String code() {
    return limit.code();
  }
}
