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
package io.qnop.spi.extract;

import java.util.List;

/**
 * The canonical, format-agnostic representation of a document version (ADR-0032), produced
 * server-side at ingest by a {@link DocumentExtractor} and stored with the immutable {@code
 * DocumentVersion}. Anchoring (ADR-0009), re-anchoring, and inter-version diff (ADR-0034) all run
 * against this model — never against a client rendering.
 *
 * @param surfaces the ordered renderable planes; at least one
 */
public record RenderedDocument(List<Surface> surfaces) {

  public RenderedDocument {
    if (surfaces == null || surfaces.isEmpty()) {
      throw new IllegalArgumentException("a rendered document has at least one surface");
    }
    surfaces = List.copyOf(surfaces);
  }
}
