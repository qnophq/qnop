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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Names the caller in the logs (issue #659, ADR-0054).
 *
 * <p>Separate from {@link RequestLogContextFilter}, and ordered <em>after</em> Spring Security
 * rather than merged with it: the request id has to exist before authentication runs, because a
 * rejected login is precisely the request someone wants to trace, while the user id can only exist
 * after it. One filter could not be on both sides of that line.
 *
 * <p>The id and nothing else. A display name or an address in a log file is a copy of personal data
 * in a place with no access control and no retention policy; the id resolves in the database, by
 * someone entitled to do it.
 */
@Component
@Order(0)
public class UserLogContextFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    UUID userId = CurrentUser.optionalUserId();
    // Anonymous traffic simply has no user — the scope leaves the key absent
    // rather than printing "null" on every login and health probe.
    try (LogContext.Scope ignored = LogContext.scope(LogContext.USER_ID, userId)) {
      chain.doFilter(request, response);
    }
  }
}
