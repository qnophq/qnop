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
package io.qnop.service.branding;

/**
 * Raised when a branding upload (or read) is rejected (issue #23). Carries the HTTP status and a
 * stable machine-readable code so the web layer can map it directly without a switch.
 */
public class BrandingValidationException extends RuntimeException {

  private final int status;
  private final String code;

  public BrandingValidationException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public static BrandingValidationException unsupportedType(String detail) {
    return new BrandingValidationException(415, "UNSUPPORTED_MEDIA_TYPE", detail);
  }

  public static BrandingValidationException tooLarge(String detail) {
    return new BrandingValidationException(413, "PAYLOAD_TOO_LARGE", detail);
  }

  public static BrandingValidationException invalidImage(String detail) {
    return new BrandingValidationException(400, "INVALID_IMAGE", detail);
  }

  public static BrandingValidationException invalidSvg(String detail) {
    return new BrandingValidationException(400, "INVALID_SVG", detail);
  }

  public static BrandingValidationException unknownSlot(String detail) {
    return new BrandingValidationException(404, "UNKNOWN_SLOT", detail);
  }

  public static BrandingValidationException notFound(String detail) {
    return new BrandingValidationException(404, "NOT_FOUND", detail);
  }
}
