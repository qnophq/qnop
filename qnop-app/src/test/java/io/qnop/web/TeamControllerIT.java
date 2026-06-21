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
import java.util.UUID;
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
 * End-to-end team-management flow against a real PostgreSQL and the real security chain (issue
 * #105): only an ADMIN reaches {@code /admin/teams}, CRUD and membership operations work, and the
 * constraints (unique name, single membership per user) are enforced.
 */
@AutoConfigureMockMvc
@Transactional
class TeamControllerIT extends AbstractIntegrationTest {

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
    mockMvc.perform(get("/api/v1/admin/teams")).andExpect(status().isUnauthorized());
  }

  @Test
  void memberIsForbidden() throws Exception {
    createUser("member", UserRole.MEMBER);
    String token = token("member");
    mockMvc
        .perform(get("/api/v1/admin/teams").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void createListAndGetTeam() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = token("root");
    String teamId = createTeam(token, "Core", "The core team");

    mockMvc
        .perform(
            get("/api/v1/admin/teams").param("q", "core").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].name").value("Core"))
        .andExpect(jsonPath("$.items[0].memberCount").value(0));

    mockMvc
        .perform(get("/api/v1/admin/teams/{id}", teamId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Core"))
        .andExpect(jsonPath("$.members").isArray())
        .andExpect(jsonPath("$.members").isEmpty());
  }

  @Test
  void rejectsDuplicateName() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = token("root");
    createTeam(token, "Core", null);

    mockMvc
        .perform(
            post("/api/v1/admin/teams")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"core\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
  }

  @Test
  void manageMembers() throws Exception {
    createUser("root", UserRole.ADMIN);
    User alice = createUser("alice", UserRole.MEMBER);
    String token = token("root");
    String teamId = createTeam(token, "Core", null);

    // Add a member as LEAD.
    mockMvc
        .perform(
            post("/api/v1/admin/teams/{id}/members", teamId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"LEAD\"}".formatted(alice.getId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.displayName").value("alice"))
        .andExpect(jsonPath("$.teamRole").value("LEAD"));

    // Adding again conflicts.
    mockMvc
        .perform(
            post("/api/v1/admin/teams/{id}/members", teamId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(alice.getId())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));

    // Detail now lists the member.
    mockMvc
        .perform(get("/api/v1/admin/teams/{id}", teamId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members[0].teamRole").value("LEAD"));

    // Change the role.
    mockMvc
        .perform(
            patch("/api/v1/admin/teams/{id}/members/{uid}", teamId, alice.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamRole").value("MEMBER"));

    // Remove the member.
    mockMvc
        .perform(
            delete("/api/v1/admin/teams/{id}/members/{uid}", teamId, alice.getId())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());
  }

  @Test
  void addMemberRejectsUnknownTeamAndUser() throws Exception {
    User alice = createUser("alice", UserRole.MEMBER);
    createUser("root", UserRole.ADMIN);
    String token = token("root");
    String teamId = createTeam(token, "Core", null);

    mockMvc
        .perform(
            post("/api/v1/admin/teams/{id}/members", UUID.randomUUID())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(alice.getId())))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            post("/api/v1/admin/teams/{id}/members", teamId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\"%s\",\"teamRole\":\"MEMBER\"}".formatted(UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateAndDeleteTeam() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = token("root");
    String teamId = createTeam(token, "Core", null);

    mockMvc
        .perform(
            patch("/api/v1/admin/teams/{id}", teamId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Platform\",\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Platform"))
        .andExpect(jsonPath("$.enabled").value(false));

    mockMvc
        .perform(delete("/api/v1/admin/teams/{id}", teamId).header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/admin/teams/{id}", teamId).header("Authorization", bearer(token)))
        .andExpect(status().isNotFound());
  }

  private String createTeam(String token, String name, String description) throws Exception {
    String body =
        description == null
            ? "{\"name\":\"%s\"}".formatted(name)
            : "{\"name\":\"%s\",\"description\":\"%s\"}".formatted(name, description);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/teams")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    Matcher matcher = ID_FIELD.matcher(result.getResponse().getContentAsString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
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
