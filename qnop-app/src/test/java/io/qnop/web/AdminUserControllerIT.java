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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
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
 * End-to-end admin user-management flow against a real PostgreSQL and the real security chain
 * (issue #104): only an ADMIN reaches {@code /admin/users}, create/edit/search work, and the
 * self-lockout guard rejects an admin disabling their own account.
 */
@AutoConfigureMockMvc
@Transactional
class AdminUserControllerIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery";
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  /**
   * Hands each login a distinct client IP so it gets its own rate-limit bucket (see loginRequest).
   */
  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
  }

  @Test
  void memberIsForbidden() throws Exception {
    createUser("member", UserRole.MEMBER);
    String token = loginAccessToken("member");

    mockMvc
        .perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminListsUsers() throws Exception {
    createUser("root", UserRole.ADMIN);
    createUser("alice", UserRole.MEMBER);
    String token = adminToken();

    // total is >= 2 rather than exactly 2: the context-start bootstrap admin (created outside the
    // test transaction, so not rolled back) also counts.
    mockMvc
        .perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page").value(0));
  }

  @Test
  void adminSearchesByQuery() throws Exception {
    createUser("root", UserRole.ADMIN);
    createUser("alice", UserRole.MEMBER);
    String token = adminToken();

    mockMvc
        .perform(
            get("/api/v1/admin/users")
                .param("q", "alice")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].username").value("alice"));
  }

  @Test
  void adminCreatesUserWithPassword() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = adminToken();

    String body =
        """
        {"displayName":"New Person","username":"newbie","email":"New@Example.com",\
        "role":"MEMBER","initialPassword":"a-strong-pass-1"}""";

    mockMvc
        .perform(
            post("/api/v1/admin/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("newbie"))
        .andExpect(jsonPath("$.email").value("new@example.com"))
        .andExpect(jsonPath("$.role").value("MEMBER"))
        .andExpect(jsonPath("$.enabled").value(true));

    assertThat(userRepository.findByUsernameAndSource("newbie", io.qnop.entity.UserSource.INTERNAL))
        .isPresent();
  }

  @Test
  void adminCreatesUserByInvitation() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = adminToken();

    String body =
        """
        {"displayName":"Invitee","username":"invitee","email":"invitee@example.com",\
        "role":"AUDITOR"}""";

    mockMvc
        .perform(
            post("/api/v1/admin/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("AUDITOR"))
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void createRejectsDuplicateEmail() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = adminToken();
    String body =
        """
        {"displayName":"Dup","username":"dup","email":"root@example.com",\
        "role":"MEMBER","initialPassword":"a-strong-pass-1"}""";

    mockMvc
        .perform(
            post("/api/v1/admin/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
  }

  @Test
  void adminUpdatesRole() throws Exception {
    createUser("root", UserRole.ADMIN);
    User target = createUser("member", UserRole.MEMBER);
    String token = adminToken();

    mockMvc
        .perform(
            patch("/api/v1/admin/users/{id}", target.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"AUDITOR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("AUDITOR"));
  }

  @Test
  void adminCannotDisableOwnAccount() throws Exception {
    User admin = createUser("root", UserRole.ADMIN);
    String token = adminToken();

    mockMvc
        .perform(
            patch("/api/v1/admin/users/{id}", admin.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
  }

  @Test
  void getUnknownUserIsNotFound() throws Exception {
    createUser("root", UserRole.ADMIN);
    String token = adminToken();

    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void sendsPasswordReset() throws Exception {
    createUser("root", UserRole.ADMIN);
    User target = createUser("member", UserRole.MEMBER);
    String token = adminToken();

    // No SMTP is configured in the IT, so the email is "skipped" and the fallback link is returned.
    mockMvc
        .perform(
            post("/api/v1/admin/users/{id}/password-reset", target.getId())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailSent").value(false))
        .andExpect(jsonPath("$.resetUrl").isNotEmpty());
  }

  @Test
  void adminDeletesUser() throws Exception {
    createUser("root", UserRole.ADMIN);
    User target = createUser("victim", UserRole.MEMBER);
    String token = adminToken();

    mockMvc
        .perform(
            delete("/api/v1/admin/users/{id}", target.getId())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}", target.getId())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void adminCannotDeleteOwnAccount() throws Exception {
    User admin = createUser("root", UserRole.ADMIN);
    String token = adminToken();

    mockMvc
        .perform(
            delete("/api/v1/admin/users/{id}", admin.getId())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_DELETE"));
  }

  @Test
  void filtersByEnabledStatus() throws Exception {
    createUser("root", UserRole.ADMIN);
    User disabled = createUser("inactive", UserRole.MEMBER);
    disabled.setEnabled(false);
    userRepository.saveAndFlush(disabled);
    String token = adminToken();

    mockMvc
        .perform(
            get("/api/v1/admin/users")
                .param("enabled", "false")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].username", everyItem(is("inactive"))))
        .andExpect(jsonPath("$.items[*].enabled", everyItem(is(false))));
  }

  @Test
  void sortsByDisplayNameDescending() throws Exception {
    createUser("root", UserRole.ADMIN);
    createUser("sortuser-a", UserRole.MEMBER);
    createUser("sortuser-b", UserRole.MEMBER);
    String token = adminToken();

    mockMvc
        .perform(
            get("/api/v1/admin/users")
                .param("q", "sortuser")
                .param("sort", "displayName,desc")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].username").value("sortuser-b"))
        .andExpect(jsonPath("$.items[1].username").value("sortuser-a"));
  }

  private String adminToken() throws Exception {
    return loginAccessToken("root");
  }

  private User createUser(String username, UserRole role) {
    User user =
        User.internal(
            username, username + "@example.com", username, passwordEncoder.encode(PASSWORD));
    user.setRole(role);
    return userRepository.saveAndFlush(user);
  }

  private MockHttpServletRequestBuilder loginRequest(String usernameOrEmail) {
    String body =
        "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(usernameOrEmail, PASSWORD);
    // Each login uses a distinct client IP. The login rate limiter (10/60s per IP, issue #18) keeps
    // in-memory counters in a singleton filter that the cached Spring context shares with the other
    // IT classes; without unique IPs this class's logins would exhaust the shared 127.0.0.1 bucket.
    String clientIp = "203.0.113." + (IP_SEQ.incrementAndGet() % 250 + 1);
    return post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .with(
            request -> {
              request.setRemoteAddr(clientIp);
              return request;
            });
  }

  private String loginAccessToken(String username) throws Exception {
    MvcResult result =
        mockMvc.perform(loginRequest(username)).andExpect(status().isOk()).andReturn();
    Matcher matcher = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
