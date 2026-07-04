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
package io.qnop.service.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * A re-openable, framework-free view of an upload (issue #361). Lets {@link DocumentIngestService}
 * stream a document without materializing it in the heap, while keeping the web layer's {@code
 * MultipartFile} out of {@code qnop-core} (ADR-0004). {@link #open()} returns a <em>fresh</em>
 * stream on each call, so ingest can sniff the magic bytes from one stream and stage the content
 * from another.
 */
public interface UploadSource {

  /** Opens a new stream over the upload's bytes; the caller closes it. */
  InputStream open() throws IOException;

  /**
   * The upload's size as declared by the transport (e.g. the multipart part length). Advisory only
   * — a client may under-report it — so it is used for a fast early rejection; the authoritative
   * size is measured while staging.
   */
  long declaredSize();
}
