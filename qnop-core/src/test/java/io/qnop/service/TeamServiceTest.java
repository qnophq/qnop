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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.repository.TeamMemberProjection;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserTeamProjection;
import io.qnop.service.TeamService.TeamMemberView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TeamService} (issue #105): name/membership guards and mapping. */
class TeamServiceTest {

  private final TeamRepository teams = mock(TeamRepository.class);
  private final TeamMembershipRepository memberships = mock(TeamMembershipRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final TeamSlugService slugs = mock(TeamSlugService.class);
  private final TeamService service = new TeamService(teams, memberships, users, slugs);

  @Test
  @DisplayName("create persists a new team and rejects a duplicate name")
  void create() {
    when(teams.existsByNameIgnoreCase("Core")).thenReturn(false);
    when(slugs.allocate("Core")).thenReturn("core");
    when(teams.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create("  Core  ", "The core team");

    assertThat(view.name()).isEqualTo("Core");
    assertThat(view.enabled()).isTrue();
    assertThat(view.memberCount()).isZero();
    // The team is persisted with the slug derived from its name.
    verify(teams).save(argThat(t -> "core".equals(t.getSlug())));

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

  // --- Team-lead self-management (issue #470) -------------------------------

  @Test
  @DisplayName(
      "viewTeam: a member views read-only, a lead may manage, an admin passes for any team")
  void viewTeam() {
    UUID teamId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    Team team = teamWithId(teamId, "Core");
    when(teams.findById(teamId)).thenReturn(Optional.of(team));
    when(memberships.findMembersByTeamId(teamId))
        .thenReturn(
            List.of(
                new TeamMemberProjection(
                    UUID.randomUUID(),
                    actor,
                    "Ada",
                    "ada",
                    "ada@x",
                    TeamRole.MEMBER,
                    Instant.EPOCH)));

    // A plain member can view but not manage, and the roster carries the slug.
    when(memberships.findByTeamIdAndUserId(teamId, actor))
        .thenReturn(Optional.of(TeamMembership.of(teamId, actor, TeamRole.MEMBER)));
    var asMember = service.viewTeam(teamId.toString(), actor, false);
    assertThat(asMember.canManage()).isFalse();
    assertThat(asMember.viewerRole()).isEqualTo("MEMBER");
    assertThat(asMember.members())
        .singleElement()
        .satisfies(m -> assertThat(m.slug()).isEqualTo("ada"));

    // A lead of the team may manage.
    when(memberships.findByTeamIdAndUserId(teamId, actor))
        .thenReturn(Optional.of(TeamMembership.of(teamId, actor, TeamRole.LEAD)));
    assertThat(service.viewTeam(teamId.toString(), actor, false).canManage()).isTrue();

    // An admin who is not a member passes, manages, and has no viewer role.
    UUID admin = UUID.randomUUID();
    when(memberships.findByTeamIdAndUserId(teamId, admin)).thenReturn(Optional.empty());
    var asAdmin = service.viewTeam(teamId.toString(), admin, true);
    assertThat(asAdmin.canManage()).isTrue();
    assertThat(asAdmin.viewerRole()).isNull();
  }

  @Test
  @DisplayName("viewTeam resolves a non-UUID ref as a slug")
  void viewTeamResolvesBySlug() {
    UUID teamId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    when(teams.findBySlugIgnoreCase("core")).thenReturn(Optional.of(teamWithId(teamId, "Core")));
    when(memberships.findMembersByTeamId(teamId)).thenReturn(List.of());
    when(memberships.findByTeamIdAndUserId(teamId, actor))
        .thenReturn(Optional.of(TeamMembership.of(teamId, actor, TeamRole.MEMBER)));

    var view = service.viewTeam("core", actor, false);

    assertThat(view.id()).isEqualTo(teamId);
    assertThat(view.viewerRole()).isEqualTo("MEMBER");
  }

  @Test
  @DisplayName("viewTeam forbids a non-member, non-admin caller")
  void viewTeamForbidsOutsider() {
    UUID teamId = UUID.randomUUID();
    UUID outsider = UUID.randomUUID();
    when(teams.findById(teamId)).thenReturn(Optional.of(teamWithId(teamId, "Core")));
    when(memberships.findByTeamIdAndUserId(teamId, outsider)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.viewTeam(teamId.toString(), outsider, false))
        .isInstanceOf(TeamAccessForbiddenException.class);
  }

  @Test
  @DisplayName("viewTeam throws not-found for an unknown slug")
  void viewTeamUnknownRef() {
    when(teams.findBySlugIgnoreCase("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.viewTeam("nope", UUID.randomUUID(), false))
        .isInstanceOf(TeamNotFoundException.class);
  }

  @Test
  @DisplayName(
      "listMyTeams maps the caller's affiliations with the team-role name and member count")
  void listMyTeams() {
    UUID userId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    when(memberships.findTeamsOfUser(userId))
        .thenReturn(List.of(new UserTeamProjection(teamId, "Core", "core", TeamRole.LEAD)));
    when(memberships.countMembersByTeamIds(List.of(teamId)))
        .thenReturn(List.of(new io.qnop.repository.TeamMemberCount(teamId, 7L)));

    assertThat(service.listMyTeams(userId))
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.teamId()).isEqualTo(teamId);
              assertThat(t.name()).isEqualTo("Core");
              assertThat(t.slug()).isEqualTo("core");
              assertThat(t.teamRole()).isEqualTo("LEAD");
              assertThat(t.memberCount()).isEqualTo(7L);
            });
  }

  @Test
  @DisplayName("a non-lead, non-admin caller is forbidden from managing a team")
  void leadGuardForbidsNonLead() {
    UUID teamId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, actor, TeamRole.LEAD))
        .thenReturn(false);

    assertThatThrownBy(() -> service.addMemberAsLead(teamId, actor, false, target, "MEMBER"))
        .isInstanceOf(TeamAccessForbiddenException.class);
    assertThatThrownBy(() -> service.setMemberRoleAsLead(teamId, actor, false, target, "LEAD"))
        .isInstanceOf(TeamAccessForbiddenException.class);
    assertThatThrownBy(() -> service.removeMemberAsLead(teamId, actor, false, target))
        .isInstanceOf(TeamAccessForbiddenException.class);
    verify(memberships, never()).save(any());
    verify(memberships, never()).delete(any());
  }

  @Test
  @DisplayName("a lead (or admin) updates the description; anyone else is refused (#509)")
  void leadUpdatesTheDescription() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Team team = Team.create("Alpha", "old text");
    when(teams.findById(teamId)).thenReturn(Optional.of(team));
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);

    service.updateDescriptionAsLead(teamId, lead, false, "Contract review crew");
    assertThat(team.getDescription()).isEqualTo("Contract review crew");

    // A blank description clears it; an admin needs no membership.
    service.updateDescriptionAsLead(teamId, UUID.randomUUID(), true, "   ");
    assertThat(team.getDescription()).isNull();

    assertThatThrownBy(() -> service.updateDescriptionAsLead(teamId, stranger, false, "nope"))
        .isInstanceOf(TeamAccessForbiddenException.class);
    assertThat(team.getDescription()).isNull();
  }

  @Test
  @DisplayName("a lead of the team may add a member; an admin passes without a lead check")
  void leadOrAdminMayAdd() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    when(teams.existsById(teamId)).thenReturn(true);
    when(users.findById(target))
        .thenReturn(Optional.of(User.internal("Al", "al@example.com", "al", "h")));
    when(memberships.existsByTeamIdAndUserId(teamId, target)).thenReturn(false);
    when(memberships.save(any())).thenAnswer(inv -> inv.getArgument(0));

    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);
    assertThat(service.addMemberAsLead(teamId, lead, false, target, "LEAD").teamRole())
        .isEqualTo("LEAD");

    // An admin passes for a team they are not a lead of.
    UUID admin = UUID.randomUUID();
    assertThat(service.addMemberAsLead(teamId, admin, true, target, "MEMBER").teamRole())
        .isEqualTo("MEMBER");
    verify(memberships, never()).existsByTeamIdAndUserIdAndTeamRole(teamId, admin, TeamRole.LEAD);
  }

  @Test
  @DisplayName("demoting or removing the team's last lead is rejected with LAST_LEAD")
  void lastLeadGuard() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID(); // the sole lead
    UUID admin = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);
    when(memberships.countByTeamIdAndTeamRole(teamId, TeamRole.LEAD)).thenReturn(1L);
    // The guard now lives in the base methods (issue #542), which resolve the membership
    // and count the remaining members before deciding.
    when(memberships.findByTeamIdAndUserId(teamId, lead))
        .thenReturn(Optional.of(TeamMembership.of(teamId, lead, TeamRole.LEAD)));
    when(memberships.countByTeamId(teamId)).thenReturn(2L); // another member remains

    // Even an admin cannot demote the sole lead (the admin actor also skips the
    // self-role-change check, so this exercises the last-lead guard in isolation) ...
    assertThatThrownBy(() -> service.setMemberRoleAsLead(teamId, admin, true, lead, "MEMBER"))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("LAST_LEAD");
    // ... and an admin cannot remove the sole lead either (self-removal does not
    // apply here — the actor is the admin, not the target).
    assertThatThrownBy(() -> service.removeMemberAsLead(teamId, admin, true, lead))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("LAST_LEAD");
    verify(memberships, never()).delete(any());
  }

  @Test
  @DisplayName("nobody changes their own role through the self-management surface (#542)")
  void leadCannotChangeOwnRole() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);

    assertThatThrownBy(() -> service.setMemberRoleAsLead(teamId, lead, false, lead, "MEMBER"))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("SELF_ROLE_CHANGE");

    // Admins included — mirroring SELF_REMOVAL: one's own role changes via the
    // admin console, never through this surface.
    UUID admin = UUID.randomUUID();
    assertThatThrownBy(() -> service.setMemberRoleAsLead(teamId, admin, true, admin, "MEMBER"))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("SELF_ROLE_CHANGE");
    // The self-check fires before the membership is ever resolved, so it blocks
    // self-demotion even when co-leads remain.
    verify(memberships, never()).findByTeamIdAndUserId(any(), any());
  }

  @Test
  @DisplayName("a lead cannot remove themselves through the self-management surface")
  void leadCannotRemoveThemselves() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);

    assertThatThrownBy(() -> service.removeMemberAsLead(teamId, lead, false, lead))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("SELF_REMOVAL");
    verify(memberships, never()).delete(any());
    // The self-check fires before the last-lead count is ever consulted, so it
    // blocks self-removal even when co-leads remain.
    verify(memberships, never()).countByTeamIdAndTeamRole(any(), any());
  }

  @Test
  @DisplayName("a lead with a co-lead may be demoted; a plain member is unaffected by the guard")
  void guardSkipsWhenAnotherLeadRemains() {
    UUID teamId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID coLead = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, actor, TeamRole.LEAD))
        .thenReturn(true);

    // Demote a co-lead while two leads exist.
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, coLead, TeamRole.LEAD))
        .thenReturn(true);
    when(memberships.countByTeamIdAndTeamRole(teamId, TeamRole.LEAD)).thenReturn(2L);
    TeamMembership coLeadMembership = TeamMembership.of(teamId, coLead, TeamRole.LEAD);
    when(memberships.findByTeamIdAndUserId(teamId, coLead))
        .thenReturn(Optional.of(coLeadMembership));
    when(users.findById(coLead))
        .thenReturn(Optional.of(User.internal("Bo", "bo@example.com", "bo", "h")));
    assertThat(service.setMemberRoleAsLead(teamId, actor, false, coLead, "MEMBER").teamRole())
        .isEqualTo("MEMBER");
    assertThat(coLeadMembership.getTeamRole()).isEqualTo(TeamRole.MEMBER);

    // Removing a plain member never trips the guard.
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, member, TeamRole.LEAD))
        .thenReturn(false);
    TeamMembership memberMembership = TeamMembership.of(teamId, member, TeamRole.MEMBER);
    when(memberships.findByTeamIdAndUserId(teamId, member))
        .thenReturn(Optional.of(memberMembership));
    service.removeMemberAsLead(teamId, actor, false, member);
    verify(memberships).delete(memberMembership);
  }

  @Test
  @DisplayName(
      "admin path: demoting or removing the last lead is rejected while members remain (#542)")
  void adminPathLastLeadGuard() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);
    when(memberships.countByTeamIdAndTeamRole(teamId, TeamRole.LEAD)).thenReturn(1L);
    when(memberships.findByTeamIdAndUserId(teamId, lead))
        .thenReturn(Optional.of(TeamMembership.of(teamId, lead, TeamRole.LEAD)));
    when(memberships.countByTeamId(teamId)).thenReturn(2L); // a second member remains

    // The admin endpoints call the base methods directly — the guard must fire there too.
    assertThatThrownBy(() -> service.setMemberRole(teamId, lead, "MEMBER"))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("LAST_LEAD");
    assertThatThrownBy(() -> service.removeMember(teamId, lead))
        .isInstanceOf(TeamConflictException.class)
        .extracting("code")
        .isEqualTo("LAST_LEAD");
    verify(memberships, never()).delete(any());
  }

  @Test
  @DisplayName(
      "admin path: removing the sole member (the last lead) empties the team and is allowed (#542)")
  void adminPathEmptyTeamAllowed() {
    UUID teamId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, lead, TeamRole.LEAD))
        .thenReturn(true);
    when(memberships.countByTeamIdAndTeamRole(teamId, TeamRole.LEAD)).thenReturn(1L);
    TeamMembership sole = TeamMembership.of(teamId, lead, TeamRole.LEAD);
    when(memberships.findByTeamIdAndUserId(teamId, lead)).thenReturn(Optional.of(sole));
    when(memberships.countByTeamId(teamId)).thenReturn(1L); // the sole member

    service.removeMember(teamId, lead); // empties the team — allowed
    verify(memberships).delete(sole);
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
