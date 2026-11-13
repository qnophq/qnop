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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.AuditScope;
import io.qnop.entity.Team;
import io.qnop.entity.TeamRole;
import io.qnop.repository.AuditEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shape of the team-membership audit events (issue #948). */
class TeamMembershipAuditTest {

  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final TeamMembershipAudit audit = new TeamMembershipAudit(auditEvents);
  private final UUID actor = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final Team team = Team.create("Legal \"core\"", null);

  private AuditEvent saved() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    return captor.getValue();
  }

  private static JsonNode detail(AuditEvent event) {
    return JsonMapper.builder().build().readTree(event.getDetail());
  }

  @Test
  @DisplayName("a role change is a SYSTEM event naming the team, the user and both roles")
  void roleChangeDetail() {
    audit.roleChanged(
        actor,
        team,
        new TeamMembershipAudit.Subject(userId, "Ivy"),
        TeamRole.MEMBER,
        TeamRole.LEAD);

    AuditEvent event = saved();
    assertThat(event.getScope()).isEqualTo(AuditScope.SYSTEM);
    assertThat(event.getDocumentId()).isNull();
    assertThat(event.getEventType()).isEqualTo(TeamMembershipAudit.MEMBER_ROLE_CHANGED);
    assertThat(event.getActorId()).isEqualTo(actor);
    JsonNode detail = detail(event);
    // Built, not concatenated: a name with quotes stays valid JSON.
    assertThat(detail.get("teamName").asString()).isEqualTo("Legal \"core\"");
    assertThat(detail.get("userId").asString()).isEqualTo(userId.toString());
    assertThat(detail.get("userName").asString()).isEqualTo("Ivy");
    assertThat(detail.get("previousRole").asString()).isEqualTo("MEMBER");
    assertThat(detail.get("role").asString()).isEqualTo("LEAD");
  }

  @Test
  @DisplayName("re-saving the same role records nothing")
  void sameRoleIsNoChange() {
    audit.roleChanged(
        actor, team, new TeamMembershipAudit.Subject(userId, "Ivy"), TeamRole.LEAD, TeamRole.LEAD);

    verify(auditEvents, never()).save(any());
  }

  @Test
  @DisplayName("an answer is attributed to the invited user, a revocation carries its reason")
  void answersAndRevocations() {
    audit.invitationDeclined(team, new TeamMembershipAudit.Subject(userId, null));
    AuditEvent declined = saved();
    assertThat(declined.getEventType()).isEqualTo(TeamMembershipAudit.INVITATION_DECLINED);
    assertThat(declined.getActorId()).isEqualTo(userId);
    assertThat(detail(declined).get("userName").isNull()).isTrue();

    org.mockito.Mockito.clearInvocations(auditEvents);
    audit.invitationRevoked(
        actor,
        team,
        new TeamMembershipAudit.Subject(userId, "Ivy"),
        TeamMembershipAudit.REASON_TEAM_DISABLED);
    AuditEvent revoked = saved();
    assertThat(revoked.getEventType()).isEqualTo(TeamMembershipAudit.INVITATION_REVOKED);
    assertThat(detail(revoked).get("reason").asString()).isEqualTo("TEAM_DISABLED");
  }
}
