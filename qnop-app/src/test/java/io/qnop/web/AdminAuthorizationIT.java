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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-cutting authorization guard for the whole Administration surface (review of issue #97):
 * proves the single {@code /api/v1/admin/** → hasRole(ADMIN)} rule on a representative read
 * endpoint of <em>every</em> admin area, through the real security chain with real login tokens (so
 * the role claim → {@code ROLE_ADMIN} mapping is exercised too). Anonymous ⇒ 401, MEMBER ⇒ 403,
 * ADMIN ⇒ 200.
 *
 * <p>This complements the per-feature ITs (settings, users, teams, branding) and closes the gap for
 * the OIDC-provider and email-template admin endpoints, which had no controller-level authz test.
 */
@AutoConfigureMockMvc
@Transactional
class AdminAuthorizationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery";
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

  /**
   * One representative GET endpoint per admin area. The rule under test guards them all uniformly.
   */
  @ParameterizedTest(name = "{0} is admin-only")
  @ValueSource(
      strings = {
        "/api/v1/admin/settings",
        "/api/v1/admin/configuration",
        "/api/v1/admin/oidc-providers",
        "/api/v1/admin/email/templates",
        "/api/v1/admin/users",
        "/api/v1/admin/teams"
      })
  void adminEndpointRequiresAdminRole(String path) throws Exception {
    createUser("auditor-of-admin", UserRole.MEMBER);
    createUser("admin-of-admin", UserRole.ADMIN);
    String memberToken = token("auditor-of-admin");
    String adminToken = token("admin-of-admin");

    // Anonymous → 401.
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());

    // A non-admin (MEMBER) → 403.
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + memberToken))
        .andExpect(status().isForbidden());

    // An ADMIN → reachable (200).
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Test
  void auditorIsAlsoForbiddenFromAdmin() throws Exception {
    createUser("plain-auditor", UserRole.AUDITOR);
    String token = token("plain-auditor");
    // AUDITOR is a non-admin global role: it must not reach the admin surface.
    mockMvc
        .perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
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
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/auth/login")
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
