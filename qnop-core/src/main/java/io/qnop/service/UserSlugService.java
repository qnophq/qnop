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
package io.qnop.service;

import io.qnop.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Allocates a free profile slug at account creation (issue #486): the {@link UserSlugs}-derived
 * base, or the first free {@code base-n} candidate on collision. Every creation path
 * (self-registration, OIDC provisioning, bootstrap admin, admin create) allocates through here so
 * derivation and collision handling never drift apart.
 *
 * <p>The check-then-save window is racy in theory; {@code ux_qnop_user_slug_lower} backstops it, so
 * two simultaneous registrations of the same display name can fail one request but never produce a
 * duplicate slug.
 */
@Service
public class UserSlugService {

  private final UserRepository users;

  public UserSlugService(UserRepository users) {
    this.users = users;
  }

  /** The first free slug for a display name (participates in the caller's transaction). */
  public String allocate(String displayName) {
    String base = UserSlugs.derive(displayName);
    for (int attempt = 1; ; attempt++) {
      String candidate = UserSlugs.candidate(base, attempt);
      if (!users.existsBySlugIgnoreCase(candidate)) {
        return candidate;
      }
    }
  }
}
