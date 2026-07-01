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
import io.qnop.entity.UserRole;
import io.qnop.repository.UserRepository;
import io.qnop.service.JwtTokenService;
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
  @Autowired JwtTokenService jwtTokenService;

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

    // The access token works before the change. Probe a genuinely protected
    // endpoint (/users/me): /config is public, so it would not prove anything.
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
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
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isUnauthorized());

    assertThat(user.getId()).isNotNull();
  }

  @Test
  void changePasswordRejectsExternalAccountsWith409() throws Exception {
    User external = userRepository.saveAndFlush(User.external("Oscar", "oscar@oidc.example.com"));
    String accessToken = jwtTokenService.issueAccessToken(external.getId());

    mockMvc
        .perform(
            post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"irrelevant\","
                        + "\"newPassword\":\"a-brand-new-strong-password\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXTERNAL_ACCOUNT"));
  }

  @Test
  void changePasswordClearsTheForcedChangeRequirement() throws Exception {
    // Regression: a user created with "must change password on first login" was sent
    // back to the change-password screen after every login because the change never
    // cleared password_change_required. A successful change must clear the flag.
    User user = createUser("nina");
    user.setPasswordChangeRequired(true);
    userRepository.saveAndFlush(user);
    String accessToken = jwtTokenService.issueAccessToken(user.getId());

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

    assertThat(userRepository.findById(user.getId()).orElseThrow().isPasswordChangeRequired())
        .isFalse();
  }

  @Test
  void passwordChangeRequiredBlocksProtectedPathsButAllowsChangePassword() throws Exception {
    // A valid JWT whose user still must change their password (pcr claim = true).
    User user = createUser("olivia");
    user.setPasswordChangeRequired(true);
    userRepository.saveAndFlush(user);
    String accessToken = jwtTokenService.issueAccessToken(user.getId());

    // Protected endpoint: blocked with 403 and the PASSWORD_CHANGE_REQUIRED code.
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

    // Allow-listed path (/api/v1/auth/**): the change-password endpoint stays reachable.
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
  }

  @Test
  void changePasswordAcceptsTheEightCharacterPolicyMinimum() throws Exception {
    // Regression: the change-password minimum must stay at 8 — matching the other
    // password fields and the UI strength meter. A 12-char minimum here rejected
    // passwords the UI had accepted, surfacing as a misleading 400 on first login.
    // Mint the token directly so the shared, IP-scoped login rate-limit bucket
    // (drained by the other tests) cannot make this assertion flaky.
    String accessToken = jwtTokenService.issueAccessToken(createUser("judy").getId());

    String body =
        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, "pass8wrd");
    mockMvc
        .perform(
            post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
  }

  @Test
  void changePasswordRejectsTooShortPasswordWithValidationEnvelope() throws Exception {
    // A password below the minimum trips bean validation, which the global handler
    // maps to a 400 carrying code VALIDATION_ERROR (the UI keys its message off this).
    // Mint the token directly to avoid the shared login rate-limit bucket.
    String accessToken = jwtTokenService.issueAccessToken(createUser("mallory").getId());

    String body =
        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, "short7x");
    mockMvc
        .perform(
            post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void adminTokenReachesAdminEndpoints() throws Exception {
    // Proves the issue-98 blocker fix end-to-end: a real login mints a token whose role claim is
    // mapped to ROLE_ADMIN by RoleJwtAuthenticationConverter, so /admin/** is reachable. A
    // @WithMockUser test cannot prove this — it injects the authority and bypasses the converter.
    createUser("grace", UserRole.ADMIN);
    String accessToken = loginAccessToken("grace");

    mockMvc
        .perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk());
  }

  @Test
  void memberTokenIsForbiddenFromAdminEndpoints() throws Exception {
    createUser("heidi", UserRole.MEMBER);
    String accessToken = loginAccessToken("heidi");

    mockMvc
        .perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void currentUserReturnsProfileForRealToken() throws Exception {
    createUser("ivan", UserRole.AUDITOR);
    String accessToken = loginAccessToken("ivan");

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("ivan"))
        .andExpect(jsonPath("$.email").value("ivan@example.com"))
        .andExpect(jsonPath("$.role").value("AUDITOR"))
        .andExpect(jsonPath("$.source").value("INTERNAL"));
  }

  @Test
  void currentUserUnauthorizedForAnonymous() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void configIsPublicForAnonymous() throws Exception {
    // OpenAPI declares GET /config as security: [] — the SPA reads it before login
    // (OIDC providers, self-registration). The slice test cannot prove this because
    // it does not load the security chain; this exercises the real filter.
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.edition").value("COMMUNITY"));
  }

  private User createUser(String username) {
    return createUser(username, UserRole.MEMBER);
  }

  private User createUser(String username, UserRole role) {
    User user =
        User.internal(
            username, username + "@example.com", username, passwordEncoder.encode(PASSWORD));
    user.setRole(role);
    return userRepository.saveAndFlush(user);
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
