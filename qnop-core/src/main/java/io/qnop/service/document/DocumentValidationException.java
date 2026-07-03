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

/**
 * A document request that must be rejected (issue #245), carrying the HTTP status and stable error
 * code the controller maps onto the uniform {@code ErrorResponse} envelope (mirrors the avatar
 * pipeline's exception shape).
 */
public class DocumentValidationException extends RuntimeException {

  private final int status;
  private final String code;

  public DocumentValidationException(int status, String code, String message) {
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

  /** Unknown document/version — also used to hide documents from non-participants (404). */
  public static DocumentValidationException notFound(String detail) {
    return new DocumentValidationException(404, "NOT_FOUND", detail);
  }

  /** A visible document, but the action is owner-only. */
  public static DocumentValidationException notOwner(String detail) {
    return new DocumentValidationException(403, "NOT_OWNER", detail);
  }

  public static DocumentValidationException unsupportedType(String detail) {
    return new DocumentValidationException(415, "UNSUPPORTED_MEDIA_TYPE", detail);
  }

  public static DocumentValidationException tooLarge(String detail) {
    return new DocumentValidationException(413, "PAYLOAD_TOO_LARGE", detail);
  }

  public static DocumentValidationException duplicateParticipant(String detail) {
    return new DocumentValidationException(409, "DUPLICATE_PARTICIPANT", detail);
  }

  public static DocumentValidationException invalidRequest(String detail) {
    return new DocumentValidationException(400, "VALIDATION_ERROR", detail);
  }

  /** The rendered representation is not (yet) available: extraction pending or failed. */
  public static DocumentValidationException renderingUnavailable(String code, String detail) {
    return new DocumentValidationException(409, code, detail);
  }
}
