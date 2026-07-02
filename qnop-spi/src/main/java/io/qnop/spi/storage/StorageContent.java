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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * A readable handle to a stored object: its byte {@code stream} plus the serving metadata. Owns the
 * stream — use it in a try-with-resources block (it is {@link AutoCloseable}) so the underlying
 * connection is released.
 *
 * @param stream the object's bytes; closed by {@link #close()}
 * @param contentLength the object size in bytes
 * @param contentType the stored MIME type
 */
public record StorageContent(InputStream stream, long contentLength, String contentType)
    implements AutoCloseable {

  @Override
  public void close() {
    try {
      stream.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
