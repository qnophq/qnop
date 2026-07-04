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
 * Thrown by {@link StorageService#stage(java.io.InputStream, String, long)} when the streamed
 * content exceeds the caller-supplied byte limit (issue #361). Staging aborts <em>before</em>
 * buffering the whole stream or uploading anything, so an over-limit upload never reaches the
 * backend. Callers translate this to their own domain error (e.g. HTTP 413).
 */
public class StorageQuotaExceededException extends RuntimeException {

  private final long limitBytes;

  public StorageQuotaExceededException(long limitBytes) {
    super("upload exceeds the maximum of " + limitBytes + " bytes");
    this.limitBytes = limitBytes;
  }

  public long limitBytes() {
    return limitBytes;
  }
}
