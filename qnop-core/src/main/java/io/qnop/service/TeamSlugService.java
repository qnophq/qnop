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

import io.qnop.repository.TeamRepository;
import org.springframework.stereotype.Service;

/**
 * Allocates a free team slug at team creation (issue #470): the {@link TeamSlugs}-derived base, or
 * the first free {@code base-n} candidate on collision. Mirrors {@link UserSlugService}. The
 * check-then-save window is racy in theory; {@code ux_team_slug_lower} backstops it, so two
 * simultaneous creates of the same name can fail one request but never produce a duplicate slug.
 */
@Service
public class TeamSlugService {

  private final TeamRepository teams;

  public TeamSlugService(TeamRepository teams) {
    this.teams = teams;
  }

  /** The first free slug for a team name (participates in the caller's transaction). */
  public String allocate(String name) {
    String base = TeamSlugs.derive(name);
    for (int attempt = 1; ; attempt++) {
      String candidate = TeamSlugs.candidate(base, attempt);
      if (!teams.existsBySlugIgnoreCase(candidate)) {
        return candidate;
      }
    }
  }
}
