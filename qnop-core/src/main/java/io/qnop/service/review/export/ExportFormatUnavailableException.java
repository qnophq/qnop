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
 * The requested format cannot be produced by this deployment (issue #639).
 *
 * <p>Its own type, and not an export failure, because the two deserve different answers. A failure
 * means something broke and retrying might work; this means the server was never able to and never
 * will be until an operator installs something. `GET /api/v1/config` says so in advance, so a
 * client that asks anyway is either out of date or hand-written — and both are better served by
 * "this server does not do that" than by a 500.
 */
public class ExportFormatUnavailableException extends RuntimeException {

  public ExportFormatUnavailableException(String format) {
    super(format + " export is not available on this server");
  }
}
