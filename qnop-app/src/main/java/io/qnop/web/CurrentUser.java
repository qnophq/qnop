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

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the authenticated end-user for {@code /users/me/**} endpoints. The principal name is the
 * JWT {@code sub} claim — a user UUID (issue #17). A principal whose name is not a UUID (e.g. an
 * API-key principal) is rejected with {@link AccessDeniedException} (HTTP 403), so machine
 * principals cannot act as a user.
 */
public final class CurrentUser {

  private CurrentUser() {}

  public static UUID requireUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String name = authentication == null ? null : authentication.getName();
    if (name == null) {
      throw new AccessDeniedException("authentication required");
    }
    try {
      return UUID.fromString(name);
    } catch (IllegalArgumentException e) {
      throw new AccessDeniedException("principal is not a user");
    }
  }

  /** Whether the caller carries the global {@code ROLE_ADMIN} authority (issue #245). */
  public static boolean isAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
  }
}
