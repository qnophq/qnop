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
package io.qnop.document;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Per-request document authorization (issue #245, ADR-0032 §5): a document is visible to its owner,
 * its participants (directly or via team membership), and admins; everyone else gets an
 * indistinguishable 404 and anonymous callers a 401. Rows are committed (no {@code @Transactional})
 * because visibility is checked through the full MockMvc security chain; cleanup in {@link
 * #cleanup()}. Requires Docker.
 */
@AutoConfigureMockMvc
class DocumentServingAuthzIT extends AbstractIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository users;
  @Autowired DocumentRepository documents;
  @Autowired DocumentVersionRepository versions;
  @Autowired ReviewParticipantRepository participants;
  @Autowired TeamRepository teams;
  @Autowired TeamMembershipRepository teamMemberships;
  @Autowired PasswordEncoder passwordEncoder;

  private final List<UUID> createdUsers = new ArrayList<>();

  private UUID owner;
  private UUID directParticipant;
  private UUID teamMember;
  private UUID stranger;
  private UUID admin;
  private UUID documentId;
  private UUID teamId;

  @BeforeEach
  void setUpFixture() {
    owner = createUser();
    directParticipant = createUser();
    teamMember = createUser();
    stranger = createUser();
    admin = createUser();

    Document document = documents.save(new Document(owner, "Authz IT"));
    documentId = document.getId();
    versions.save(
        new DocumentVersion(documentId, 1, "sha256/authz", "authz", "application/pdf", 4L, owner));

    Team team = teams.save(Team.create("authz-team-" + UUID.randomUUID(), null));
    teamId = team.getId();
    teamMemberships.save(TeamMembership.of(teamId, teamMember, TeamRole.MEMBER));

    participants.save(ReviewParticipant.forUser(documentId, directParticipant));
    participants.save(ReviewParticipant.forTeam(documentId, teamId));
  }

  @AfterEach
  void cleanup() {
    documents.findById(documentId).ifPresent(documents::delete); // cascades versions+participants
    teams.findById(teamId).ifPresent(teams::delete);
    createdUsers.forEach(id -> users.findById(id).ifPresent(users::delete));
    createdUsers.clear();
  }

  @Test
  @DisplayName("owner, direct participant, team member, and admin can read the document")
  void authorizedCallersSeeTheDocument() throws Exception {
    for (UUID allowed : List.of(owner, directParticipant, teamMember)) {
      mockMvc
          .perform(get("/api/v1/documents/{id}", documentId).with(asUser(allowed)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.title").value("Authz IT"));
    }
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asAdmin(admin)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("a non-participant gets 404 on every read surface — never a 403")
  void strangerGetsIndistinguishable404() throws Exception {
    mockMvc
        .perform(get("/api/v1/documents/{id}", documentId).with(asUser(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mockMvc
        .perform(get("/api/v1/documents/{id}/versions", documentId).with(asUser(stranger)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/versions/1/rendered", documentId).with(asUser(stranger)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/versions/1/original", documentId).with(asUser(stranger)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("anonymous callers get 401")
  void anonymousGets401() throws Exception {
    mockMvc.perform(get("/api/v1/documents/{id}", documentId)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/documents/{id}/versions/1/original", documentId))
        .andExpect(status().isUnauthorized());
  }

  private UUID createUser() {
    String name = "authz-" + UUID.randomUUID();
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
