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

import org.springframework.stereotype.Service;

/**
 * Allocates a free team slug at team creation (issue #470): the {@link TeamSlugs}-derived base, or
 * the first free {@code base-n} candidate on collision. Mirrors {@link UserSlugService}.
 *
 * <p>Candidates are probed through the flat {@link SlugNamespace} (issue #595, ADR-0048): a slug
 * must be free among teams AND users, and the namespace's advisory lock closes the check-then-save
 * race that the per-table unique indexes cannot guard across tables.
 */
@Service
public class TeamSlugService {

  private final SlugNamespace namespace;

  public TeamSlugService(SlugNamespace namespace) {
    this.namespace = namespace;
  }

  /** The first free slug for a team name (participates in the caller's transaction). */
  public String allocate(String name) {
    String base = TeamSlugs.derive(name);
    for (int attempt = 1; ; attempt++) {
      String candidate = TeamSlugs.candidate(base, attempt);
      if (namespace.lockAndCheckFree(candidate)) {
        return candidate;
      }
    }
  }
}
