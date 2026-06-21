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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.TeamService.TeamMemberView;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TeamService} (issue #105): name/membership guards and mapping. */
class TeamServiceTest {

  private final TeamRepository teams = mock(TeamRepository.class);
  private final TeamMembershipRepository memberships = mock(TeamMembershipRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final TeamService service = new TeamService(teams, memberships, users);

  @Test
  @DisplayName("create persists a new team and rejects a duplicate name")
  void create() {
    when(teams.existsByNameIgnoreCase("Core")).thenReturn(false);
    when(teams.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create("  Core  ", "The core team");

    assertThat(view.name()).isEqualTo("Core");
    assertThat(view.enabled()).isTrue();
    assertThat(view.memberCount()).isZero();

    when(teams.existsByNameIgnoreCase("Core")).thenReturn(true);
    assertThatThrownBy(() -> service.create("Core", null))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("NAME_TAKEN");
  }

  @Test
  @DisplayName("update rejects renaming onto another team's name but allows keeping its own")
  void updateNameConflict() {
    UUID id = UUID.randomUUID();
    Team team = teamWithId(id, "Core");
    when(teams.findById(id)).thenReturn(Optional.of(team));
    when(memberships.countMembersByTeamIds(any())).thenReturn(java.util.List.of());

    // Another team already owns "Platform".
    when(teams.findByNameIgnoreCase("Platform"))
        .thenReturn(Optional.of(teamWithId(UUID.randomUUID(), "Platform")));
    assertThatThrownBy(() -> service.update(id, "Platform", null, null))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("NAME_TAKEN");

    // Renaming to a name owned by the same team is allowed.
    when(teams.findByNameIgnoreCase("Core")).thenReturn(Optional.of(team));
    var view = service.update(id, "Core", "desc", false);
    assertThat(view.name()).isEqualTo("Core");
    assertThat(view.enabled()).isFalse();
  }

  @Test
  @DisplayName("get and delete throw for an unknown team")
  void unknownTeam() {
    UUID id = UUID.randomUUID();
    when(teams.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(TeamNotFoundException.class);
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(TeamNotFoundException.class);
  }

  @Test
  @DisplayName("addMember validates the team, the user and duplicate membership")
  void addMember() {
    UUID teamId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(teams.existsById(teamId)).thenReturn(false);
    assertThatThrownBy(() -> service.addMember(teamId, userId, "MEMBER"))
        .isInstanceOf(TeamNotFoundException.class);

    when(teams.existsById(teamId)).thenReturn(true);
    when(users.findById(userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addMember(teamId, userId, "MEMBER"))
        .isInstanceOf(UserNotFoundException.class);

    User user = User.internal("Alice", "alice@example.com", "alice", "h");
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(memberships.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
    assertThatThrownBy(() -> service.addMember(teamId, userId, "MEMBER"))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("ALREADY_MEMBER");

    when(memberships.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);
    when(memberships.save(any())).thenAnswer(inv -> inv.getArgument(0));
    TeamMemberView view = service.addMember(teamId, userId, "LEAD");
    assertThat(view.displayName()).isEqualTo("Alice");
    assertThat(view.teamRole()).isEqualTo("LEAD");
  }

  @Test
  @DisplayName("setMemberRole and removeMember throw when the membership is missing")
  void missingMembership() {
    UUID teamId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(memberships.findByTeamIdAndUserId(teamId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.setMemberRole(teamId, userId, "LEAD"))
        .isInstanceOf(TeamNotFoundException.class);
    assertThatThrownBy(() -> service.removeMember(teamId, userId))
        .isInstanceOf(TeamNotFoundException.class);
    verify(memberships, never()).delete(any());
  }

  @Test
  @DisplayName("setMemberRole updates the role of an existing member")
  void setMemberRole() {
    UUID teamId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TeamMembership membership = TeamMembership.of(teamId, userId, TeamRole.MEMBER);
    when(memberships.findByTeamIdAndUserId(teamId, userId)).thenReturn(Optional.of(membership));
    when(users.findById(userId))
        .thenReturn(Optional.of(User.internal("Bob", "bob@example.com", "bob", "h")));

    TeamMemberView view = service.setMemberRole(teamId, userId, "LEAD");

    assertThat(membership.getTeamRole()).isEqualTo(TeamRole.LEAD);
    assertThat(view.teamRole()).isEqualTo("LEAD");
  }

  private static Team teamWithId(UUID id, String name) {
    Team team = Team.create(name, null);
    try {
      var field = Team.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(team, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return team;
  }
}
