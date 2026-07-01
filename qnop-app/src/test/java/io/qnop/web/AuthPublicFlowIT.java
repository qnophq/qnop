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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Controller-level integration coverage for the public auth flows (issue #188): registration, email
 * verification, and self-service password reset, driven through the real security chain with
 * MockMvc + Testcontainers. These endpoints are CSRF-exempt (only refresh/logout require the
 * double-submit token), so no {@code csrf()} post-processor is used.
 *
 * <p>Not {@code @Transactional}: {@code ApplicationSettingsService.update} commits in its own
 * transaction and refreshes the in-memory snapshot only after that commit, so the feature-flag
 * toggle must really commit for the controller to observe it. State is restored in {@link
 * #cleanup()} instead, and each request uses a fresh client IP so the per-IP rate-limit buckets
 * (which outlive a rolled-back transaction) never collide across tests. Requires Docker.
 */
@AutoConfigureMockMvc
class AuthPublicFlowIT extends AbstractIntegrationTest {

  private static final String REGISTRATION_KEY =
      ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED.getKey();
  private static final String PASSWORD = "a-good-password";
  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ApplicationSettingsService settings;

  @AfterEach
  void cleanup() {
    // update() commits independently of the test, so explicitly restore the default and drop the
    // users these tests committed (their tokens cascade on delete).
    settings.update(Map.of(REGISTRATION_KEY, "false"), null);
    deleteInternalUser("alice-reg");
    deleteInternalUser("existing-reset");
  }

  @Test
  void registerReturns404WhenSelfRegistrationDisabled() throws Exception {
    // AUTH_SELF_REGISTRATION_ENABLED defaults to false — the endpoint is disguised as 404.
    mockMvc
        .perform(registerBody("someone", "someone@example.com", PASSWORD).with(freshIp()))
        .andExpect(status().isNotFound());
  }

  @Test
  void registerReturns200ForValidBodyWhenEnabled() throws Exception {
    settings.update(Map.of(REGISTRATION_KEY, "true"), null);

    mockMvc
        .perform(registerBody("alice-reg", "alice-reg@example.com", PASSWORD).with(freshIp()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationRequired").value(true))
        .andExpect(jsonPath("$.message").isNotEmpty());

    assertThat(userRepository.findByUsernameAndSource("alice-reg", UserSource.INTERNAL))
        .isPresent();
  }

  @Test
  void registerRejectsInvalidBodyWithValidationError() throws Exception {
    // Bean Validation runs at argument resolution, before the controller's feature-flag check, so
    // an invalid body is a 400 VALIDATION_ERROR regardless of whether registration is enabled.
    String body = "{\"username\":\"ab\",\"email\":\"not-an-email\",\"password\":\"short\"}";
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(freshIp()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void verifyEmailReturns400ForInvalidToken() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/verify-email").param("token", "not-a-real-token"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void forgotPasswordReturns204ForBothKnownAndUnknownEmail() throws Exception {
    User existing =
        User.internal(
            "Existing",
            "existing-reset@example.com",
            "existing-reset",
            passwordEncoder.encode(PASSWORD));
    existing.setEnabled(true);
    userRepository.saveAndFlush(existing);

    // Anti-enumeration: the response is identical whether or not the address is registered.
    mockMvc
        .perform(forgotBody("existing-reset@example.com").with(freshIp()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(forgotBody("nobody@example.com").with(freshIp()))
        .andExpect(status().isNoContent());
  }

  @Test
  void resetPasswordReturns400ForInvalidToken() throws Exception {
    String body = "{\"token\":\"not-a-real-token\",\"newPassword\":\"" + PASSWORD + "\"}";
    mockMvc
        .perform(
            post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(freshIp()))
        .andExpect(status().isBadRequest());
  }

  private MockHttpServletRequestBuilder registerBody(
      String username, String email, String password) {
    String body =
        "{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}"
            .formatted(username, email, password);
    return post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private MockHttpServletRequestBuilder forgotBody(String email) {
    return post("/api/v1/auth/forgot-password")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\"}".formatted(email));
  }

  private void deleteInternalUser(String username) {
    userRepository
        .findByUsernameAndSource(username, UserSource.INTERNAL)
        .ifPresent(userRepository::delete);
  }

  /** A distinct client IP per request so per-IP rate-limit buckets never collide across tests. */
  private static RequestPostProcessor freshIp() {
    String ip = "203.0.113." + (IP_SEQ.incrementAndGet() % 250 + 1);
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }
}
