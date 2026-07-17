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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end team-lead self-management ({@code /api/v1/teams/**}) against a real PostgreSQL and the
 * real security chain (issue #470): a LEAD manages only their own team (a non-lead MEMBER gets 403,
 * a lead of another team gets 403), an ADMIN passes for any team, and the last-lead guardrail
 * blocks stripping a team's final lead. The teams are set up via the admin surface (an ADMIN
 * token).
 */
@AutoConfigureMockMvc
@Transactional
class MyTeamsControllerIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery";
  private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/teams/mine")).andExpect(status().isUnauthorized());
  }

  @Test
  void leadManagesOwnTeamAndSeesItInMine() throws Exception {
    String admin = token(createUser("root", UserRole.ADMIN));
    User lead = createUser("lead", UserRole.MEMBER);
    User bob = createUser("bob", UserRole.MEMBER);
    User carol = createUser("carol", UserRole.MEMBER);
    String teamId = createTeam(admin, "Core");
    addMember(admin, teamId, lead, "LEAD");
    addMember(admin, teamId, bob, "MEMBER");

    String leadToken = token("lead");

    // The lead sees the team in their own list, flagged LEAD.
    mockMvc
        .perform(get("/api/v1/teams/mine").header("Authorization", bearer(leadToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Core"))
        .andExpect(jsonPath("$.items[0].teamRole").value("LEAD"));

    // Detail is readable by the lead.
    mockMvc
        .perform(get("/api/v1/teams/{id}", teamId).header("Authorization", bearer(leadToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members").isArray());

    // Add carol.
    mockMvc
        .perform(
            post("/api/v1/teams/{id}/members", teamId)
                .header("Authorization", bearer(leadToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(carol.getId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.teamRole").value("MEMBER"));

    // Promote carol to LEAD, then demote again (a co-lead remains, so allowed).
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", teamId, carol.getId())
                .header("Authorization", bearer(leadToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"LEAD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamRole").value("LEAD"));
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", teamId, carol.getId())
                .header("Authorization", bearer(leadToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamRole").value("MEMBER"));

    // Remove bob.
    mockMvc
        .perform(
            delete("/api/v1/teams/{id}/members/{uid}", teamId, bob.getId())
                .header("Authorization", bearer(leadToken)))
        .andExpect(status().isNoContent());
  }

  @Test
  void leadCannotManageAnotherTeam() throws Exception {
    String admin = token(createUser("root", UserRole.ADMIN));
    User lead = createUser("lead", UserRole.MEMBER);
    User outsider = createUser("outsider", UserRole.MEMBER);
    String own = createTeam(admin, "Own");
    addMember(admin, own, lead, "LEAD");
    String other = createTeam(admin, "Other");

    String leadToken = token("lead");

    mockMvc
        .perform(get("/api/v1/teams/{id}", other).header("Authorization", bearer(leadToken)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/teams/{id}/members", other)
                .header("Authorization", bearer(leadToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(outsider.getId())))
        .andExpect(status().isForbidden());
    // The authz-critical mutations on another team are rejected before any
    // membership lookup, so an arbitrary target id still yields 403 (not 404).
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", other, outsider.getId())
                .header("Authorization", bearer(leadToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"LEAD\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            delete("/api/v1/teams/{id}/members/{uid}", other, outsider.getId())
                .header("Authorization", bearer(leadToken)))
        .andExpect(status().isForbidden());
  }

  @Test
  void plainMemberIsForbiddenFromManaging() throws Exception {
    String admin = token(createUser("root", UserRole.ADMIN));
    User lead = createUser("lead", UserRole.MEMBER);
    User bob = createUser("bob", UserRole.MEMBER);
    String teamId = createTeam(admin, "Core");
    addMember(admin, teamId, lead, "LEAD");
    addMember(admin, teamId, bob, "MEMBER");

    String bobToken = token("bob");

    // Bob sees the team in their own list (as MEMBER) but cannot manage it.
    mockMvc
        .perform(get("/api/v1/teams/mine").header("Authorization", bearer(bobToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].teamRole").value("MEMBER"));
    mockMvc
        .perform(get("/api/v1/teams/{id}", teamId).header("Authorization", bearer(bobToken)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/teams/{id}/members", teamId)
                .header("Authorization", bearer(bobToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(bob.getId())))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminManagesAnyTeamThroughTheLeadSurface() throws Exception {
    String admin = token(createUser("root", UserRole.ADMIN));
    User alice = createUser("alice", UserRole.MEMBER);
    String teamId = createTeam(admin, "Core");

    // The admin is not a member of the team, yet passes the lead surface.
    mockMvc
        .perform(
            post("/api/v1/teams/{id}/members", teamId)
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"LEAD\"}".formatted(alice.getId())))
        .andExpect(status().isCreated());
    mockMvc
        .perform(get("/api/v1/teams/{id}", teamId).header("Authorization", bearer(admin)))
        .andExpect(status().isOk());
  }

  @Test
  void lastLeadCannotBeDemotedOrRemoved() throws Exception {
    String admin = token(createUser("root", UserRole.ADMIN));
    User lead = createUser("lead", UserRole.MEMBER);
    String teamId = createTeam(admin, "Core");
    addMember(admin, teamId, lead, "LEAD");

    String leadToken = token("lead");

    // The sole lead cannot demote themselves (self-lockout) ...
    mockMvc
        .perform(
            patch("/api/v1/teams/{id}/members/{uid}", teamId, lead.getId())
                .header("Authorization", bearer(leadToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_LEAD"));

    // ... nor be removed, even by an admin, through this surface.
    mockMvc
        .perform(
            delete("/api/v1/teams/{id}/members/{uid}", teamId, lead.getId())
                .header("Authorization", bearer(admin)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_LEAD"));
  }

  @Test
  void currentUserExposesTeamLeadFlag() throws Exception {
    String admin = token(createUser("root", UserRole.ADMIN));
    User lead = createUser("lead", UserRole.MEMBER);
    User bob = createUser("bob", UserRole.MEMBER);
    String teamId = createTeam(admin, "Core");
    addMember(admin, teamId, lead, "LEAD");
    addMember(admin, teamId, bob, "MEMBER");

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", bearer(token("lead"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamLead").value(true));
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", bearer(token("bob"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamLead").value(false));
  }

  private String createTeam(String token, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/teams")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn();
    Matcher matcher = ID_FIELD.matcher(result.getResponse().getContentAsString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private void addMember(String token, String teamId, User user, String role) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/teams/{id}/members", teamId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"%s\"}".formatted(user.getId(), role)))
        .andExpect(status().isCreated());
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private User createUser(String username, UserRole role) {
    User user =
        User.internal(
            username, username + "@example.com", username, passwordEncoder.encode(PASSWORD));
    user.setRole(role);
    return userRepository.saveAndFlush(user);
  }

  private String token(User user) throws Exception {
    return token(user.getUsername());
  }

  private String token(String username) throws Exception {
    String clientIp = "203.0.113." + (IP_SEQ.incrementAndGet() % 250 + 1);
    String body = "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
    MockHttpServletRequestBuilder login =
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .with(
                request -> {
                  request.setRemoteAddr(clientIp);
                  return request;
                });
    MvcResult result = mockMvc.perform(login).andExpect(status().isOk()).andReturn();
    Matcher matcher = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
