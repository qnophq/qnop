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

import io.qnop.observability.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the review being acted on into the log context (issue #659, ADR-0054).
 *
 * <p>Here rather than in thirty controller methods: by the time a handler runs, Spring has already
 * parsed the URI template, so one interceptor covers every {@code /documents/{documentId}/…} route
 * — including the ones added next year, which is the part a per-method call could not promise.
 *
 * <p>It reads the parsed template variable rather than the raw path, so a client cannot inject
 * anything: a value that is not a UUID is dropped instead of logged.
 *
 * <p>The request filter clears the whole MDC afterwards, so this deliberately does not implement
 * {@code afterCompletion} — two places removing the same key is one place too many to reason about.
 */
@Component
public class DocumentLogContextInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  /**
   * Registers itself.
   *
   * <p>Rather than having {@code ApiPathConfig} depend on it: that class is about path mapping, and
   * a web slice test importing it should not have to know that logging exists.
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  private static final String DOCUMENT_ID = "documentId";

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(variables instanceof Map<?, ?> parsed)) {
      return true;
    }
    Object documentId = parsed.get(DOCUMENT_ID);
    if (documentId != null && isUuid(documentId.toString())) {
      MDC.put(LogContext.DOCUMENT_ID, documentId.toString());
    }
    return true;
  }

  /**
   * Only a UUID goes in.
   *
   * <p>The same path also accepts a slug (issue #411), and a slug is user-chosen text — it would be
   * personal data in a log line, and it would be attacker-controlled content in a field operators
   * read as fact.
   */
  private static boolean isUuid(String value) {
    try {
      java.util.UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
