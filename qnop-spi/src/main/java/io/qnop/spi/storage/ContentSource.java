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

/**
 * Content that can be read more than once (issue #983): every {@link #open()} returns a new stream
 * from the first byte. A {@link StorageProvider} that retries a failed upload opens it again for
 * each attempt, which a one-shot {@link InputStream} cannot offer.
 */
@FunctionalInterface
public interface ContentSource {

  /**
   * Opens the content from its start. The caller closes the returned stream.
   *
   * @throws IOException if the content cannot be opened
   */
  InputStream open() throws IOException;
}
