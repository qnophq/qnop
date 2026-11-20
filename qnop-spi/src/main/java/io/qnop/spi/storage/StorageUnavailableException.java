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
package io.qnop.spi.storage;

/**
 * The backing store could not be reached, or answered with a failure of its own, such as a server
 * error or throttling (issue #983). Retrying later may succeed, unlike a failure of the request
 * itself, which surfaces as a plain {@link StorageException}.
 */
public class StorageUnavailableException extends StorageException {

  public StorageUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
