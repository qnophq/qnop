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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.UserRepository;
import io.qnop.web.security.RefreshTokenCookieFactory;
import jakarta.servlet.http.Cookie;
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
 * End-to-end auth flow against a real PostgreSQL (issue #17): login issues an access token plus a
 * refresh cookie, refresh rotates the token, a replayed refresh token is rejected (reuse
 * detection), logout clears the cookie, and a password change invalidates existing access tokens.
 * Uses MockMvc with the {@code csrf()} post-processor for the double-submit-protected endpoints.
 */
@AutoConfigureMockMvc
@Transactional
class AuthControllerIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery";

  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void loginIssuesAccessTokenAndRefreshCookie() throws Exception {
    createUser("alice");

    MvcResult result =
        mockMvc
            .perform(loginRequest("alice", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresInSeconds").value(900))
            .andReturn();

    Cookie cookie = result.getResponse().getCookie(RefreshTokenCookieFactory.COOKIE_NAME);
    assertThat(cookie).isNotNull();
    assertThat(cookie.getValue()).isNotBlank();
    assertThat(cookie.isHttpOnly()).isTrue();
  }

  @Test
  void rejectsInvalidCredentials() throws Exception {
    createUser("bob");

    mockMvc.perform(loginRequest("bob", "wrong-password")).andExpect(status().isUnauthorized());
  }

  @Test
  void refreshRotatesTheToken() throws Exception {
    createUser("carol");
    Cookie refresh = login("carol");

    MvcResult rotated =
        mockMvc
            .perform(post("/api/v1/auth/refresh").cookie(refresh).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn();

    Cookie newCookie = rotated.getResponse().getCookie(RefreshTokenCookieFactory.COOKIE_NAME);
    assertThat(newCookie).isNotNull();
    assertThat(newCookie.getValue()).isNotEqualTo(refresh.getValue());
  }

  @Test
  void rejectsReusedRefreshToken() throws Exception {
    createUser("dave");
    Cookie refresh = login("dave");

    // First rotation consumes the token.
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(refresh).with(csrf()))
        .andExpect(status().isOk());

    // Replaying the now-revoked token is rejected (and revokes the whole family).
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(refresh).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutClearsTheCookie() throws Exception {
    createUser("erin");
    Cookie refresh = login("erin");

    MvcResult result =
        mockMvc
            .perform(post("/api/v1/auth/logout").cookie(refresh).with(csrf()))
            .andExpect(status().isNoContent())
            .andReturn();

    Cookie cleared = result.getResponse().getCookie(RefreshTokenCookieFactory.COOKIE_NAME);
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();
  }

  @Test
  void changePasswordInvalidatesExistingAccessTokens() throws Exception {
    User user = createUser("frank");
    String accessToken = loginAccessToken("frank");

    // The access token works before the change.
    mockMvc
        .perform(get("/api/v1/config").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk());

    String body =
        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
            .formatted(PASSWORD, "a-brand-new-strong-password");
    mockMvc
        .perform(
            post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    // The previously issued access token is now rejected (passwordInvalidatedBefore bumped).
    mockMvc
        .perform(get("/api/v1/config").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isUnauthorized());

    assertThat(user.getId()).isNotNull();
  }

  private User createUser(String username) {
    return userRepository.saveAndFlush(
        User.internal(
            username, username + "@example.com", username, passwordEncoder.encode(PASSWORD)));
  }

  private MockHttpServletRequestBuilder loginRequest(String usernameOrEmail, String password) {
    String body =
        "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(usernameOrEmail, password);
    return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private Cookie login(String username) throws Exception {
    MvcResult result =
        mockMvc.perform(loginRequest(username, PASSWORD)).andExpect(status().isOk()).andReturn();
    return result.getResponse().getCookie(RefreshTokenCookieFactory.COOKIE_NAME);
  }

  private String loginAccessToken(String username) throws Exception {
    MvcResult result =
        mockMvc.perform(loginRequest(username, PASSWORD)).andExpect(status().isOk()).andReturn();
    Matcher matcher = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
