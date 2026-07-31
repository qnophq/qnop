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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives every request an identity in the logs (issue #659, ADR-0054).
 *
 * <p>Without this a report of "it failed yesterday afternoon" has nothing to search by. The request
 * id is minted here and returned in {@code X-Request-Id}, so a user can quote the number from a
 * failure and an operator can pull the whole request — and, through the document scope, the async
 * work it set off.
 *
 * <p>Ordered ahead of Spring Security so that authentication failures are logged with a request id
 * too; the user id is therefore read <em>after</em> the chain has run its authentication, which is
 * why it is set inside the filter rather than at entry.
 *
 * <p><strong>The finally block is the point of this class.</strong> Tomcat pools threads: a context
 * left behind attributes the next request — a different person — to whoever came before. That is a
 * false record in the log someone later reads as evidence, so the context is cleared
 * unconditionally and the whole MDC is wiped rather than the keys this filter happens to know
 * about.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogContextFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLogContextFilter.class);

  static final String REQUEST_ID_HEADER = "X-Request-Id";

  /** Short enough to read out over the phone, long enough not to collide within a day's logs. */
  private static final int REQUEST_ID_LENGTH = 12;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = requestIdOf(request);
    MDC.put(LogContext.REQUEST_ID, requestId);
    MDC.put(LogContext.METHOD, request.getMethod());
    response.setHeader(REQUEST_ID_HEADER, requestId);
    // The path is deliberately NOT set here. At this point only the raw URI exists, and
    // it carries whatever the caller put in it — a profile slug is a display name. The
    // interceptor sets the matched route template instead, once Spring has parsed it.
    long started = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      logCompletion(request, response, started);
      MDC.clear();
    }
  }

  /**
   * One line per request at DEBUG: what came in, what went out, how long it took.
   *
   * <p>Cheap to leave in and the first thing worth having when a flow is under investigation — a
   * 404 nobody expected, or a call that was slow rather than broken.
   *
   * <p>The route template is preferred over the raw URI for the reason above. An unmatched request
   * has no template, and there the raw path is logged: a 404 you cannot identify is not worth
   * logging at all, and a path that matched nothing is junk or a probe rather than one of our
   * users' names.
   */
  private void logCompletion(
      HttpServletRequest request, HttpServletResponse response, long startedNanos) {
    if (!log.isDebugEnabled()) {
      return;
    }
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String route = pattern != null ? pattern.toString() : request.getRequestURI() + " (unmatched)";
    log.debug(
        "{} {} -> {} [{} ms]",
        request.getMethod(),
        route,
        response.getStatus(),
        (System.nanoTime() - startedNanos) / 1_000_000);
  }

  /**
   * An inbound id is honoured so a reverse proxy or a client can stitch a call across services;
   * anything unreasonable is replaced rather than trusted, because this value ends up in log files
   * and must not carry newlines or a caller's free text.
   */
  private static String requestIdOf(HttpServletRequest request) {
    String inbound = request.getHeader(REQUEST_ID_HEADER);
    if (inbound != null && !inbound.isBlank() && inbound.length() <= 64 && isSafe(inbound)) {
      return inbound;
    }
    return UUID.randomUUID().toString().replace("-", "").substring(0, REQUEST_ID_LENGTH);
  }

  private static boolean isSafe(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean allowed =
          Character.isLetterOrDigit(character) || character == '-' || character == '_';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }
}
