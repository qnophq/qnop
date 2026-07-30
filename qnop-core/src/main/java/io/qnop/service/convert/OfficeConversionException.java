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

/**
 * A document could not be converted.
 *
 * <p>Usually an environment failure rather than a user error — no converter installed, a run that
 * did not finish in time — and those are worth retrying. {@link #isPermanent()} marks the one case
 * that is not: the converter ran to completion and produced nothing, which is how the tool says it
 * could not read the document. Retrying that forever would leave a version stuck in PENDING (issue
 * #343), so it is failed instead.
 */
public class OfficeConversionException extends RuntimeException {

  private final boolean permanent;

  public OfficeConversionException(String message) {
    this(message, null, false);
  }

  public OfficeConversionException(String message, Throwable cause) {
    this(message, cause, false);
  }

  private OfficeConversionException(String message, Throwable cause, boolean permanent) {
    super(message, cause);
    this.permanent = permanent;
  }

  /** The converter ran and could not read the document; a retry produces the same answer. */
  public static OfficeConversionException unreadableDocument(String message) {
    return new OfficeConversionException(message, null, true);
  }

  public boolean isPermanent() {
    return permanent;
  }
}
