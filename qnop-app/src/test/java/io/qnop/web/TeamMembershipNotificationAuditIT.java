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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.NotificationType;
import io.qnop.repository.NotificationRepository;
import io.qnop.service.TeamMembershipAudit;
import io.qnop.service.UserSettingKey;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Team invitations reach the invited user, and every membership change lands in the audit trail
 * (issue #948), end to end against PostgreSQL. Team Beta is led by Mia (MEMBER_ID) and has Max
 * (MEMBER2_ID) as a member; Avery (AUDITOR_ID) is the outsider being invited. Requires Docker.
 */
class TeamMembershipNotificationAuditIT extends SeededIntegrationTest {

  private static final UUID BETA = TEAM_BETA_ID;
  private static final String INVITEE_EMAIL = "auditor@qnop.test";

  @MockitoBean private MailService mail;

  @Autowired private NotificationRepository notifications;
  @Autowired private JdbcTemplate jdbc;

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private ResultActions invite(UUID userId) throws Exception {
    return mockMvc.perform(
        as(post("/api/v1/teams/{id}/members", BETA), MEMBER_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(userId)));
  }

  /** The team's audit events in commit order: event type, actor and the subject user. */
  private List<Map<String, Object>> teamAudit() {
    return jdbc.queryForList(
        "SELECT event_type, actor_id, scope, detail->>'userId' AS user_id,"
            + " detail->>'role' AS role, detail->>'previousRole' AS previous_role,"
            + " detail->>'reason' AS reason, detail->>'teamName' AS team_name"
            + " FROM audit_event WHERE detail->>'teamId' = ? ORDER BY chain_seq",
        BETA.toString());
  }

  /** Delivery is asynchronous (after commit, on the notification executor). */
  private static void eventually(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition within 5s").isLessThan(deadline);
      Thread.sleep(50);
    }
  }

  private static List<String> types(List<Map<String, Object>> rows) {
    return rows.stream().map(row -> (String) row.get("event_type")).toList();
  }

  @Test
  @DisplayName("an invitation reaches the invited user in the inbox and by e-mail")
  void invitationNotifiesInAppAndByMail() throws Exception {
    invite(AUDITOR_ID).andExpect(status().isCreated());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.TEAM_INVITATION), eq(INVITEE_EMAIL), vars.capture(), eq("en"));
    assertThat(vars.getValue())
        .containsEntry("teamName", "Beta")
        .containsEntry("actorName", "Mia Member");
    assertThat((String) vars.getValue().get("actionUrl")).endsWith("/my-teams");

    eventually(() -> notifications.countByRecipientIdAndReadAtIsNull(AUDITOR_ID) > 0);
    mockMvc
        .perform(as(get("/api/v1/notifications"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].type").value("TEAM_INVITATION"))
        .andExpect(jsonPath("$.items[0].teamName").value("Beta"))
        .andExpect(jsonPath("$.items[0].actorName").value("Mia Member"))
        .andExpect(jsonPath("$.items[0].actionPath").value("/my-teams"))
        .andExpect(jsonPath("$.items[0].actionTarget").value("TEAMS"))
        .andExpect(jsonPath("$.items[0].accessible").value(true));
  }

  @Test
  @DisplayName("the invitation opt-out silences the mail but not the inbox")
  void optOutSilencesOnlyTheMail() throws Exception {
    storeUserSetting(AUDITOR_ID, UserSettingKey.EMAIL_TEAM_INVITATIONS.getKey(), "false");

    invite(AUDITOR_ID).andExpect(status().isCreated());

    eventually(
        () ->
            notifications.findAll().stream()
                .anyMatch(
                    n ->
                        n.getRecipientId().equals(AUDITOR_ID)
                            && n.getType() == NotificationType.TEAM_INVITATION));
    verify(mail, after(500).never())
        .sendMailFromTemplate(eq(MailTemplateKey.TEAM_INVITATION), any(), anyMap(), any());
  }

  @Test
  @DisplayName("a direct add by an admin notifies nobody — it is not an invitation")
  void directAddIsNotAnInvitation() throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/admin/teams/{id}/members", BETA), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(AUDITOR_ID)))
        .andExpect(status().isCreated());

    verify(mail, after(500).never())
        .sendMailFromTemplate(eq(MailTemplateKey.TEAM_INVITATION), any(), anyMap(), any());
    assertThat(notifications.countByRecipientIdAndReadAtIsNull(AUDITOR_ID)).isZero();
  }

  @Test
  @DisplayName("invite, decline, revoke and accept are each audited with their actor")
  void invitationLifecycleIsAudited() throws Exception {
    invite(AUDITOR_ID).andExpect(status().isCreated());
    mockMvc
        .perform(as(post("/api/v1/teams/invitations/{id}/decline", BETA), AUDITOR_ID))
        .andExpect(status().isNoContent());
    invite(AUDITOR_ID).andExpect(status().isCreated());
    mockMvc
        .perform(as(delete("/api/v1/teams/{id}/invitations/{uid}", BETA, AUDITOR_ID), MEMBER_ID))
        .andExpect(status().isNoContent());
    invite(AUDITOR_ID).andExpect(status().isCreated());
    mockMvc
        .perform(as(post("/api/v1/teams/invitations/{id}/accept", BETA), AUDITOR_ID))
        .andExpect(status().isOk());

    List<Map<String, Object>> rows = teamAudit();
    assertThat(types(rows))
        .containsExactly(
            TeamMembershipAudit.INVITATION_CREATED,
            TeamMembershipAudit.INVITATION_DECLINED,
            TeamMembershipAudit.INVITATION_CREATED,
            TeamMembershipAudit.INVITATION_REVOKED,
            TeamMembershipAudit.INVITATION_CREATED,
            TeamMembershipAudit.INVITATION_ACCEPTED);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("scope")).isEqualTo("SYSTEM");
              assertThat(row.get("user_id")).isEqualTo(AUDITOR_ID.toString());
              assertThat(row.get("team_name")).isEqualTo("Beta");
            });
    assertThat(rows.get(0).get("actor_id")).isEqualTo(MEMBER_ID);
    assertThat(rows.get(1).get("actor_id")).isEqualTo(AUDITOR_ID);
    assertThat(rows.get(3).get("actor_id")).isEqualTo(MEMBER_ID);
    assertThat(rows.get(3).get("reason")).isEqualTo(TeamMembershipAudit.REASON_REVOKED);
    assertThat(rows.get(5).get("actor_id")).isEqualTo(AUDITOR_ID);
  }

  @Test
  @DisplayName("direct adds, LEAD grants, demotions and removals are audited")
  void memberChangesAreAudited() throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/admin/teams/{id}/members", BETA), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(AUDITOR_ID)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            as(patch("/api/v1/admin/teams/{id}/members/{uid}", BETA, MEMBER2_ID), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"LEAD\"}"))
        .andExpect(status().isOk());
    // The lead demotes the co-lead again and removes the newcomer.
    mockMvc
        .perform(
            as(patch("/api/v1/teams/{id}/members/{uid}", BETA, MEMBER2_ID), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(as(delete("/api/v1/teams/{id}/members/{uid}", BETA, AUDITOR_ID), MEMBER_ID))
        .andExpect(status().isNoContent());

    List<Map<String, Object>> rows = teamAudit();
    assertThat(types(rows))
        .containsExactly(
            TeamMembershipAudit.MEMBER_ADDED,
            TeamMembershipAudit.MEMBER_ROLE_CHANGED,
            TeamMembershipAudit.MEMBER_ROLE_CHANGED,
            TeamMembershipAudit.MEMBER_REMOVED);
    assertThat(rows.get(0).get("actor_id")).isEqualTo(ADMIN_ID);
    assertThat(rows.get(0).get("role")).isEqualTo("MEMBER");
    assertThat(rows.get(1).get("actor_id")).isEqualTo(ADMIN_ID);
    assertThat(rows.get(1).get("previous_role")).isEqualTo("MEMBER");
    assertThat(rows.get(1).get("role")).isEqualTo("LEAD");
    assertThat(rows.get(2).get("actor_id")).isEqualTo(MEMBER_ID);
    assertThat(rows.get(2).get("role")).isEqualTo("MEMBER");
    assertThat(rows.get(3).get("user_id")).isEqualTo(AUDITOR_ID.toString());

    // The audit surface lists and filters the new types.
    mockMvc
        .perform(
            as(get("/api/v1/audit/events"), AUDITOR_ID)
                .param("eventType", TeamMembershipAudit.MEMBER_ROLE_CHANGED))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].scope").value("SYSTEM"));
  }

  @Test
  @DisplayName("disabling a team audits each invitation it drops")
  void disablingTheTeamAuditsDroppedInvitations() throws Exception {
    invite(AUDITOR_ID).andExpect(status().isCreated());

    mockMvc
        .perform(
            as(patch("/api/v1/admin/teams/{id}", BETA), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk());

    List<Map<String, Object>> rows = teamAudit();
    assertThat(types(rows))
        .containsExactly(
            TeamMembershipAudit.INVITATION_CREATED, TeamMembershipAudit.INVITATION_REVOKED);
    assertThat(rows.get(1).get("actor_id")).isEqualTo(ADMIN_ID);
    assertThat(rows.get(1).get("reason")).isEqualTo(TeamMembershipAudit.REASON_TEAM_DISABLED);
  }
}
