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

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Team;
import io.qnop.entity.TeamRole;
import io.qnop.repository.AuditEventRepository;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records team-membership changes in the SYSTEM audit stream (issue #948, ADR-0064 amendment):
 * invitations sent, accepted, declined and revoked, members added and removed, and team-role
 * changes — including every grant and removal of {@code LEAD}, which is a privilege.
 *
 * <p>Written through the ordinary repository inside the caller's transaction, so a rolled-back
 * change leaves no entry, and the database seals each row into the hash chain at commit (issue
 * #928, ADR-0043) exactly as it does for every other event.
 *
 * <p>The detail snapshots the team's and the user's <em>names</em> next to their ids: an entry is
 * read long after the fact, by which time the team may be renamed or deleted and the user gone, and
 * the trail must still say who was let into what (ADR-0042 shows names, never raw ids).
 */
@Component
public class TeamMembershipAudit {

  /** A lead invited a user; the invitation grants nothing yet. */
  public static final String INVITATION_CREATED = "team.invitation.created";

  /** The invited user accepted and became a member. */
  public static final String INVITATION_ACCEPTED = "team.invitation.accepted";

  /** The invited user declined. */
  public static final String INVITATION_DECLINED = "team.invitation.declined";

  /**
   * An invitation was withdrawn: by a lead or admin ({@link #REASON_REVOKED}), with its team
   * ({@link #REASON_TEAM_DISABLED}) or by a direct add of the same user ({@link
   * #REASON_SUPERSEDED}).
   */
  public static final String INVITATION_REVOKED = "team.invitation.revoked";

  /** A member was added directly by an admin (including a new team's first lead). */
  public static final String MEMBER_ADDED = "team.member.added";

  /** A member was removed by a lead or an admin. */
  public static final String MEMBER_REMOVED = "team.member.removed";

  /** A member's team role changed; {@code previousRole} and {@code role} say which way. */
  public static final String MEMBER_ROLE_CHANGED = "team.member.role_changed";

  /** {@code reason} of a revocation a lead or an admin asked for. */
  public static final String REASON_REVOKED = "REVOKED";

  /** {@code reason} of the invitations dropped because an admin disabled the team. */
  public static final String REASON_TEAM_DISABLED = "TEAM_DISABLED";

  /** {@code reason} of an invitation made moot by an admin adding the user directly. */
  public static final String REASON_SUPERSEDED = "SUPERSEDED";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final AuditEventRepository auditEvents;

  public TeamMembershipAudit(AuditEventRepository auditEvents) {
    this.auditEvents = auditEvents;
  }

  public void invitationCreated(UUID actorId, Team team, Subject user) {
    record(
        INVITATION_CREATED,
        actorId,
        team,
        user,
        detail -> detail.put("role", TeamRole.MEMBER.name()));
  }

  /** The invited user is the actor. */
  public void invitationAccepted(Team team, Subject user) {
    record(INVITATION_ACCEPTED, user.id(), team, user, detail -> {});
  }

  /** The invited user is the actor. */
  public void invitationDeclined(Team team, Subject user) {
    record(INVITATION_DECLINED, user.id(), team, user, detail -> {});
  }

  public void invitationRevoked(UUID actorId, Team team, Subject user, String reason) {
    record(INVITATION_REVOKED, actorId, team, user, detail -> detail.put("reason", reason));
  }

  public void memberAdded(UUID actorId, Team team, Subject user, TeamRole role) {
    record(MEMBER_ADDED, actorId, team, user, detail -> detail.put("role", role.name()));
  }

  /** {@code role} is the role the member held when removed. */
  public void memberRemoved(UUID actorId, Team team, Subject user, TeamRole role) {
    record(MEMBER_REMOVED, actorId, team, user, detail -> detail.put("role", role.name()));
  }

  /** Records only a real change — re-saving the same role is nothing an auditor needs to see. */
  public void roleChanged(
      UUID actorId, Team team, Subject user, TeamRole previousRole, TeamRole role) {
    if (previousRole == role) {
      return;
    }
    record(
        MEMBER_ROLE_CHANGED,
        actorId,
        team,
        user,
        detail -> detail.put("previousRole", previousRole.name()).put("role", role.name()));
  }

  private void record(
      String eventType, UUID actorId, Team team, Subject user, Consumer<ObjectNode> extra) {
    ObjectNode detail = MAPPER.createObjectNode();
    detail.put("teamId", team.getId() == null ? null : team.getId().toString());
    detail.put("teamName", team.getName());
    detail.put("userId", user.id().toString());
    detail.put("userName", user.name());
    extra.accept(detail);
    auditEvents.save(AuditEvent.system(eventType, actorId, detail.toString()));
  }

  /** The user a membership change is about: the id, plus the name to snapshot (null if unknown). */
  public record Subject(UUID id, String name) {}
}
