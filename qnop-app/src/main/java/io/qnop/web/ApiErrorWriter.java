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
package io.qnop.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes the uniform {@code ErrorResponse} envelope (issue #45) to a raw {@link
 * HttpServletResponse}. Used by the security entry point (401), the access-denied handler (403),
 * and the rate-limit filters (429) — the places that short-circuit the request <em>before</em> the
 * dispatcher, where a {@code @ControllerAdvice} cannot reach. Controller-level errors build the
 * same {@code ErrorResponse} through the normal MVC pipeline.
 */
public final class ApiErrorWriter {

  private ApiErrorWriter() {}

  public static void write(
      HttpServletResponse response, HttpStatus status, String code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String json =
        "{\"code\":\""
            + escape(code)
            + "\",\"message\":\""
            + escape(message)
            + "\",\"timestamp\":\""
            + OffsetDateTime.now()
            + "\"}";
    response.getWriter().write(json);
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
