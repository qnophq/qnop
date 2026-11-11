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
package io.qnop.web.security.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The per-identifier auth throttles (issue #899, ADR-0027) through the real security chain: each
 * request comes from a different client address, so the per-IP filters never fire and only the
 * identifier bucket can refuse. Low per-identifier limits, generous per-IP ones. Requires Docker.
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "qnop.auth.rate-limit.login.max-attempts=1000",
      "qnop.auth.rate-limit.register.max-attempts=1000",
      "qnop.auth.rate-limit.forgot-password.max-attempts=1000",
      "qnop.auth.rate-limit.reset-password.max-attempts=1000",
      "qnop.auth.rate-limit.login-account.max-attempts=3",
      "qnop.auth.rate-limit.register-email.max-attempts=2",
      "qnop.auth.rate-limit.forgot-password-email.max-attempts=2",
      "qnop.auth.rate-limit.reset-password-token.max-attempts=2"
    })
class IdentifierRateLimitIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "a-good-password";
  private static final String REGISTRATION_KEY =
      ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED.getKey();
  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ApplicationSettingsService settings;

  @AfterEach
  void cleanup() {
    settings.update(Map.of(REGISTRATION_KEY, "false"), null);
    for (String username : List.of("limited-login", "limited-ok", "limited-reg")) {
      userRepository
          .findByUsernameAndSource(username, UserSource.INTERNAL)
          .ifPresent(userRepository::delete);
    }
  }

  @Test
  void failedLoginsAreCappedPerAccountAcrossClientAddresses() throws Exception {
    createUser("limited-login", "limited-login@example.com");

    for (int i = 0; i < 3; i++) {
      perform(login("limited-login", "wrong-password")).andExpect(status().isUnauthorized());
    }

    // The bucket is drained: even the right password is refused, from yet another address.
    expectRateLimited(perform(login("limited-login", PASSWORD)), LoginRateLimitFilter.MESSAGE);
  }

  @Test
  void anUnknownAccountIsThrottledExactlyLikeAKnownOne() throws Exception {
    for (int i = 0; i < 3; i++) {
      perform(login("no-such-account", "wrong-password")).andExpect(status().isUnauthorized());
    }

    expectRateLimited(perform(login("no-such-account", PASSWORD)), LoginRateLimitFilter.MESSAGE);
  }

  @Test
  void successfulLoginsDoNotDrainTheAccountBucket() throws Exception {
    createUser("limited-ok", "limited-ok@example.com");

    for (int i = 0; i < 5; i++) {
      perform(login("limited-ok", PASSWORD)).andExpect(status().isOk());
    }
  }

  @Test
  void registrationIsCappedPerEmail() throws Exception {
    settings.update(Map.of(REGISTRATION_KEY, "true"), null);

    perform(register("limited-reg", "limited-reg@example.com")).andExpect(status().isOk());
    // Taken or not, the acknowledgement is the same — and so is the throttle.
    perform(register("limited-reg", "LIMITED-REG@example.com")).andExpect(status().isOk());

    expectRateLimited(
        perform(register("limited-reg", "limited-reg@example.com")),
        RegisterRateLimitFilter.MESSAGE);
  }

  @Test
  void resetRequestsAreCappedPerEmail() throws Exception {
    for (int i = 0; i < 2; i++) {
      perform(forgot("capped-forgot@example.com")).andExpect(status().isNoContent());
    }

    expectRateLimited(
        perform(forgot("capped-forgot@example.com")), ForgotPasswordRateLimitFilter.MESSAGE);
  }

  @Test
  void resetAttemptsAreCappedPerToken() throws Exception {
    for (int i = 0; i < 2; i++) {
      perform(reset("capped-token")).andExpect(status().isBadRequest());
    }

    expectRateLimited(perform(reset("capped-token")), ResetPasswordRateLimitFilter.MESSAGE);
  }

  private void createUser(String username, String email) {
    User user = User.internal("Limited", email, username, passwordEncoder.encode(PASSWORD));
    user.setEnabled(true);
    userRepository.saveAndFlush(user);
  }

  private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    String ip = "198.51.100." + (IP_SEQ.incrementAndGet() % 250 + 1);
    return mockMvc.perform(
        request.with(
            r -> {
              r.setRemoteAddr(ip);
              return r;
            }));
  }

  private static void expectRateLimited(ResultActions result, String message) throws Exception {
    result
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.message").value(message));
  }

  private static MockHttpServletRequestBuilder login(String usernameOrEmail, String password) {
    return json(
        "/api/v1/auth/login",
        "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(usernameOrEmail, password));
  }

  private static MockHttpServletRequestBuilder register(String username, String email) {
    return json(
        "/api/v1/auth/register",
        "{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}"
            .formatted(username, email, PASSWORD));
  }

  private static MockHttpServletRequestBuilder forgot(String email) {
    return json("/api/v1/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email));
  }

  private static MockHttpServletRequestBuilder reset(String token) {
    return json(
        "/api/v1/auth/reset-password",
        "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, PASSWORD));
  }

  private static MockHttpServletRequestBuilder json(String path, String body) {
    return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
