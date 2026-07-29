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
package io.qnop.service.review.export;

/**
 * Turns a finished {@link AnnotationExportModel} into the bytes of one file format (issue #635).
 *
 * <p>Implementations are deliberately dependency-free: they receive data and return bytes. That is
 * what makes them unit-testable without a database, and it is also the guarantee that a new format
 * cannot reach past the visibility rules the model already applied — there is nothing here to
 * query.
 *
 * <p>Formats differ in presentation, never in content. A renderer may lay the same facts out
 * differently, elide what the model says was not selected, or give a field more room than a
 * spreadsheet cell allows — but it must not show an annotation the model does not carry.
 */
public interface AnnotationExportRenderer {

  /** Which format this renderer produces. */
  AnnotationExportFormat format();

  /**
   * Whether this server can actually produce the format right now.
   *
   * <p>Almost always yes — a renderer that only assembles bytes has nothing to be unavailable. It
   * is a question at all because one format converts through an external process (issue #639), and
   * a deployment without that installed must not be offered a download it cannot deliver.
   */
  default boolean isAvailable() {
    return true;
  }

  /**
   * Renders the whole model.
   *
   * @throws java.io.IOException when the underlying document library fails to assemble the file
   */
  byte[] render(AnnotationExportModel model) throws java.io.IOException;
}
