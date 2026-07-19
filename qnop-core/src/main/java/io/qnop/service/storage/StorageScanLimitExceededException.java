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
package io.qnop.service.storage;

/**
 * Raised when a storage-consistency scan streams more objects than the configured circuit-breaker
 * limit (issue #523): a pathological bucket fails fast rather than hanging. Mapped to HTTP 409 with
 * the stable code {@link #CODE} so the dashboard can surface a readable warning.
 */
public class StorageScanLimitExceededException extends RuntimeException {

  public static final String CODE = "STORAGE_SCAN_LIMIT";

  public StorageScanLimitExceededException(String message) {
    super(message);
  }

  public String getCode() {
    return CODE;
  }
}
