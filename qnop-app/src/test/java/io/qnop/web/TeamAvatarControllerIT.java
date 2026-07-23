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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.repository.TeamAvatarRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.avatar.AvatarLimits;
import io.qnop.service.avatar.TeamAvatarService;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the team-avatar endpoints (issue #509) through the real security chain: an admin and a
 * team LEAD may set/replace/remove a team's picture (a non-lead MEMBER and a stranger get 403); the
 * read path is public and serves an ETag/304; validation rejects bad types/sizes; the admin team
 * DTO exposes the avatar URL; and deleting a team cascades its avatar away. Requires Docker
 * (Testcontainers).
 */
@Transactional
class TeamAvatarControllerIT extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired TeamMembershipRepository memberships;
  @Autowired TeamAvatarRepository teamAvatars;
  @Autowired TeamAvatarService teamAvatarService;
  @Autowired EntityManager entityManager;

  private MockMvc mockMvc;
  private UUID adminId;
  private UUID leadId;
  private UUID memberId;
  private UUID strangerId;
  private UUID teamId;
  private byte[] png;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    adminId = newUser("Admin");
    leadId = newUser("Lead");
    memberId = newUser("Member");
    strangerId = newUser("Stranger");

    teamId = teams.saveAndFlush(Team.create("Core", "The core team")).getId();
    memberships.saveAndFlush(TeamMembership.of(teamId, leadId, TeamRole.LEAD));
    memberships.saveAndFlush(TeamMembership.of(teamId, memberId, TeamRole.MEMBER));

    BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    png = out.toByteArray();
  }

  private UUID newUser(String name) {
    String tag = "team-avatar-" + UUID.randomUUID();
    return users.saveAndFlush(User.internal(name, tag + "@example.com", tag, "hash")).getId();
  }

  private JwtRequestPostProcessor as(UUID userId, String role) {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority(role));
  }

  private MockMultipartFile file(String name, String contentType, byte[] bytes) {
    return new MockMultipartFile("file", name, contentType, bytes);
  }

  // --- Admin surface -------------------------------------------------------

  @Test
  void adminUploadThenPubliclyServedWithEtagAnd304() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(adminId, "ROLE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.containsString(teamId.toString())));

    MvcResult served =
        mockMvc
            .perform(get("/api/v1/teams/" + teamId + "/avatar"))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andReturn();

    String etag = served.getResponse().getHeader("ETag");
    mockMvc
        .perform(get("/api/v1/teams/" + teamId + "/avatar").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void memberCannotUploadViaAdminPath() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(memberId, "ROLE_MEMBER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminTeamDetailExposesAvatarUrl() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(adminId, "ROLE_ADMIN")))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/admin/teams/" + teamId).with(as(adminId, "ROLE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.containsString("/avatar?v=")));
  }

  // --- Lead self-manage surface (authz matrix) -----------------------------

  @Test
  void leadUploadsForOwnTeam() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(leadId, "ROLE_MEMBER")))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/teams/" + teamId + "/avatar")).andExpect(status().isOk());
  }

  @Test
  void adminMayUploadViaTheSelfManagePath() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(adminId, "ROLE_ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void nonLeadMemberIsForbidden() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(memberId, "ROLE_MEMBER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void strangerIsForbidden() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(strangerId, "ROLE_MEMBER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousUploadIsUnauthorized() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/teams/" + teamId + "/avatar").file(file("a.png", "image/png", png)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void leadRemoveRestoresAbsentState() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/teams/" + teamId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(as(leadId, "ROLE_MEMBER")))
        .andExpect(status().isOk());

    mockMvc
        .perform(delete("/api/v1/teams/" + teamId + "/avatar").with(as(leadId, "ROLE_MEMBER")))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/v1/teams/" + teamId + "/avatar")).andExpect(status().isNotFound());
  }

  // --- Read path + validation ----------------------------------------------

  @Test
  void publicGetMissingAvatarReturns404() throws Exception {
    mockMvc.perform(get("/api/v1/teams/" + teamId + "/avatar")).andExpect(status().isNotFound());
  }

  @Test
  void uploadRejectsUnsupportedType() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/teams/" + teamId + "/avatar")
                .file(file("note.txt", "text/plain", new byte[] {1, 2, 3, 4, 5, 6, 7, 8}))
                .with(as(adminId, "ROLE_ADMIN")))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void uploadRejectsOversizedPayload() throws Exception {
    byte[] tooBig = new byte[(int) AvatarLimits.MAX_SIZE_BYTES + 1];
    mockMvc
        .perform(
            multipart("/api/v1/admin/teams/" + teamId + "/avatar")
                .file(file("big.png", "image/png", tooBig))
                .with(as(adminId, "ROLE_ADMIN")))
        .andExpect(status().isPayloadTooLarge());
  }

  // --- Cascade -------------------------------------------------------------

  @Test
  void deletingATeamCascadesItsAvatar() {
    teamAvatarService.store(teamId, png, adminId);
    assertThat(teamAvatars.findById(teamId)).isPresent();

    teams.deleteById(teamId);
    // The FK cascade runs at the DB (outside Hibernate), so flush the parent delete and clear the
    // L1 cache before re-reading the child, or the stale row would look present.
    entityManager.flush();
    entityManager.clear();

    assertThat(teamAvatars.findById(teamId)).isEmpty();
  }
}
