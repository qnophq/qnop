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

import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assignable-principal directory (issue #292): enabled users and teams for picking review
 * participants. Available to every authenticated user by deliberate Community decision — it exposes
 * display names only, and the user search does not match on email so the directory can never
 * confirm an address.
 */
@Service
public class PrincipalDirectoryService {

  private final UserRepository users;
  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;

  public PrincipalDirectoryService(
      UserRepository users, TeamRepository teams, TeamMembershipRepository memberships) {
    this.users = users;
    this.teams = teams;
    this.memberships = memberships;
  }

  @Transactional(readOnly = true)
  public List<PrincipalView> search(String query, int size) {
    String like =
        query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    Pageable limit = PageRequest.of(0, size);
    Stream<PrincipalView> userViews =
        users.searchEnabledPrincipals(like, limit).stream()
            .map(
                user ->
                    new PrincipalView(user.getId(), false, user.getSlug(), user.getDisplayName()));
    Stream<PrincipalView> teamViews =
        teams.searchEnabledPrincipals(like, limit).stream()
            .map(team -> new PrincipalView(team.getId(), true, null, team.getName()));
    return Stream.concat(userViews, teamViews)
        .sorted(Comparator.comparing(view -> view.displayName().toLowerCase(Locale.ROOT)))
        .limit(size)
        .toList();
  }

  /**
   * A team's members, display names only (issue #403): lets a participant list unfold a team to see
   * who reviews behind it — the same visibility rule as the directory itself.
   */
  @Transactional(readOnly = true)
  public List<PrincipalView> teamMembers(UUID teamId) {
    if (!teams.existsById(teamId)) {
      throw new TeamNotFoundException("team not found: " + teamId);
    }
    return memberships.findMembersByTeamId(teamId).stream()
        .map(
            member ->
                new PrincipalView(member.userId(), false, member.slug(), member.displayName()))
        .toList();
  }

  /** An assignable principal — an enabled user or team; {@code slug} is null for teams (#486). */
  public record PrincipalView(UUID id, boolean team, String slug, String displayName) {}
}
