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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamInvitationRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Consent for lead-initiated team enrolment (issue #936, ADR-0064) end to end against a real
 * PostgreSQL and the security chain: a lead's add only invites, the invited user sees nothing of
 * the team or its reviews until accepting, declining and revoking remove the invitation, and only
 * an admin grants LEAD. Rows are committed (visibility runs through the full chain); cleanup in
 * {@link #cleanup()}. Requires Docker.
 */
@AutoConfigureMockMvc
class TeamInvitationConsentIT extends AbstractIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired DocumentVersionRepository versions;
  @Autowired ReviewParticipantRepository participants;
  @Autowired TeamRepository teams;
  @Autowired TeamMembershipRepository teamMemberships;
  @Autowired TeamInvitationRepository invitations;
  @Autowired PasswordEncoder passwordEncoder;

  private final List<UUID> createdUsers = new ArrayList<>();

  private UUID lead;
  private UUID invitee;
  private UUID stranger;
  private UUID admin;
  private UUID teamId;
  private UUID documentId;

  @BeforeEach
  void setUpFixture() {
    UUID owner = createUser();
    lead = createUser();
    invitee = createUser();
    stranger = createUser();
    admin = createUser();

    Team team = teams.save(Team.create("consent-team-" + UUID.randomUUID(), null));
    teamId = team.getId();
    teamMemberships.save(TeamMembership.of(teamId, lead, TeamRole.LEAD));

    Document document = documents.save(new Document(owner, "Consent IT"));
    documentId = document.getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/consent", "consent", "application/pdf", 4L, owner));
    participants.save(ReviewParticipant.forTeam(documentId, teamId));
  }

  @AfterEach
  void cleanup() {
    documents.findById(documentId).ifPresent(documents::delete);
    teams.findById(teamId).ifPresent(teams::delete); // cascades memberships + invitations
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
    createdUsers.clear();
  }

  @Test
  @DisplayName("an invited user has no access to the team or its reviews until accepting")
  void invitedUserHasNoAccessUntilAccepting() throws Exception {
    invite(invitee)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("INVITED"))
        .andExpect(jsonPath("$.teamRole").value("MEMBER"))
        .andExpect(jsonPath("$.email").doesNotExist());

    // Nothing is granted yet: no review, no team, no "My Teams" entry.
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(invitee)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/teams/{id}", teamId).with(asUser(invitee)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/teams/mine").with(asUser(invitee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    assertThat(teamMemberships.existsByTeamIdAndUserId(teamId, invitee)).isFalse();

    // The invitation is listed for the invited user ...
    mockMvc
        .perform(get("/api/v1/teams/invitations").with(asUser(invitee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].teamId").value(teamId.toString()))
        .andExpect(jsonPath("$.items[0].invitedAt").exists());
    // ... and shown to the lead as pending, outside the member roster.
    mockMvc
        .perform(get("/api/v1/teams/{id}", teamId).with(asUser(lead)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.pendingInvitations[0].userId").value(invitee.toString()))
        .andExpect(jsonPath("$.pendingInvitations[0].status").value("INVITED"));

    // Accepting grants the membership, and with it the team's reviews.
    mockMvc
        .perform(post("/api/v1/teams/invitations/{id}/accept", teamId).with(asUser(invitee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamId").value(teamId.toString()))
        .andExpect(jsonPath("$.teamRole").value("MEMBER"))
        .andExpect(jsonPath("$.memberCount").value(2));
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(invitee)))
        .andExpect(status().isOk());
    assertThat(invitations.existsByTeamIdAndUserId(teamId, invitee)).isFalse();
  }

  @Test
  @DisplayName("declining or revoking removes the invitation; accepting afterwards is a 404")
  void declineAndRevokeRemoveTheInvitation() throws Exception {
    invite(invitee).andExpect(status().isCreated());
    mockMvc
        .perform(post("/api/v1/teams/invitations/{id}/decline", teamId).with(asUser(invitee)))
        .andExpect(status().isNoContent());
    assertThat(invitations.existsByTeamIdAndUserId(teamId, invitee)).isFalse();
    mockMvc
        .perform(post("/api/v1/teams/invitations/{id}/accept", teamId).with(asUser(invitee)))
        .andExpect(status().isNotFound());

    invite(invitee).andExpect(status().isCreated());
    invite(invitee)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_INVITED"));
    mockMvc
        .perform(delete("/api/v1/teams/{id}/invitations/{uid}", teamId, invitee).with(asUser(lead)))
        .andExpect(status().isNoContent());
    assertThat(invitations.existsByTeamIdAndUserId(teamId, invitee)).isFalse();
    mockMvc
        .perform(post("/api/v1/teams/invitations/{id}/accept", teamId).with(asUser(invitee)))
        .andExpect(status().isNotFound());
    assertThat(teamMemberships.existsByTeamIdAndUserId(teamId, invitee)).isFalse();
  }

  @Test
  @DisplayName("only the invited user can accept; only a lead or admin can revoke")
  void othersCannotAnswerOrRevoke() throws Exception {
    invite(invitee).andExpect(status().isCreated());

    mockMvc
        .perform(post("/api/v1/teams/invitations/{id}/accept", teamId).with(asUser(stranger)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/v1/teams/{id}/invitations/{uid}", teamId, invitee).with(asUser(stranger)))
        .andExpect(status().isForbidden());
    assertThat(invitations.existsByTeamIdAndUserId(teamId, invitee)).isTrue();

    // The admin console revokes too.
    mockMvc
        .perform(
            delete("/api/v1/admin/teams/{id}/invitations/{uid}", teamId, invitee)
                .with(asAdmin(admin)))
        .andExpect(status().isNoContent());
    assertThat(invitations.existsByTeamIdAndUserId(teamId, invitee)).isFalse();
  }

  @Test
  @DisplayName("a lead cannot grant LEAD; an admin can, and an admin's add is immediate")
  void onlyAnAdminGrantsLead() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/teams/{id}/members", teamId)
                .with(asUser(lead))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"LEAD\"}".formatted(invitee)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("LEAD_GRANT_REQUIRES_ADMIN"));
    assertThat(invitations.existsByTeamIdAndUserId(teamId, invitee)).isFalse();

    // An admin's add through the same surface is immediate.
    mockMvc
        .perform(
            post("/api/v1/teams/{id}/members", teamId)
                .with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(invitee)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(invitee)))
        .andExpect(status().isOk());

    // The lead may not promote ...
    String promote = "{\"teamRole\":\"LEAD\"}";
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", teamId, invitee)
                .with(asUser(lead))
                .contentType(MediaType.APPLICATION_JSON)
                .content(promote))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("LEAD_GRANT_REQUIRES_ADMIN"));
    assertThat(teamMemberships.existsByTeamIdAndUserIdAndTeamRole(teamId, invitee, TeamRole.LEAD))
        .isFalse();
    // ... an admin may, and the lead can still demote.
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", teamId, invitee)
                .with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(promote))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamRole").value("LEAD"));
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", teamId, invitee)
                .with(asUser(lead))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamRole").value("MEMBER"));
  }

  private org.springframework.test.web.servlet.ResultActions invite(UUID userId) throws Exception {
    return mockMvc.perform(
        post("/api/v1/teams/{id}/members", teamId)
            .with(asUser(lead))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(userId)));
  }

  private UUID createUser() {
    String name = "consent-" + UUID.randomUUID();
    User user =
        User.internal(name, name + "@example.com", name, passwordEncoder.encode("irrelevant-pw"));
    user.setEnabled(true);
    UUID id = users.saveAndFlush(user).getId();
    createdUsers.add(id);
    return id;
  }

  private static RequestPostProcessor asUser(UUID userId) {
    return jwt().jwt(j -> j.subject(userId.toString()));
  }

  private static RequestPostProcessor asAdmin(UUID userId) {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
