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
package io.qnop.service.avatar;

/**
 * Raised when an avatar upload (or read) is rejected (issue #117). Carries the HTTP status and a
 * stable machine-readable code so the web layer can map it directly without a switch.
 */
public class AvatarValidationException extends RuntimeException {

  private final int status;
  private final String code;

  public AvatarValidationException(int status, String code, String message) {
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

  public static AvatarValidationException unsupportedType(String detail) {
    return new AvatarValidationException(415, "UNSUPPORTED_MEDIA_TYPE", detail);
  }

  public static AvatarValidationException tooLarge(String detail) {
    return new AvatarValidationException(413, "PAYLOAD_TOO_LARGE", detail);
  }

  public static AvatarValidationException invalidImage(String detail) {
    return new AvatarValidationException(400, "INVALID_IMAGE", detail);
  }

  public static AvatarValidationException userNotFound(String detail) {
    return new AvatarValidationException(404, "USER_NOT_FOUND", detail);
  }

  public static AvatarValidationException notFound(String detail) {
    return new AvatarValidationException(404, "NOT_FOUND", detail);
  }
}
