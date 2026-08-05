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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/**
 * Self-service password reset (issue #163). The forgot-password endpoint is uniformly 204 to avoid
 * account enumeration; reset-password rejects unknown/invalid tokens and short passwords. The
 * happy-path reset needs the emailed raw token, which the suite cannot observe, so it is covered at
 * the service layer rather than here.
 */
class SeededPasswordResetIT extends SeededIntegrationTest {

  private static final String FORGOT = "/api/v1/auth/forgot-password";

  @org.springframework.beans.factory.annotation.Autowired
  private io.qnop.service.ApplicationSettingsService settings;

  /**
   * Application settings are shared state that outlives a single test — the service caches them and
   * the row stays written. A case that switches the reset off therefore has to switch it back, or
   * every later test in this class runs against a deployment it did not ask for.
   */
  @org.junit.jupiter.api.AfterEach
  void restoreResetSetting() {
    settings.update(java.util.Map.of("auth.password_reset_enabled", "true"), null);
  }

  private static final String RESET = "/api/v1/auth/reset-password";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "admin@qnop.test", // known, enabled, internal
        "disabled@qnop.test", // known but disabled
        "external@qnop.test", // known but external (no password)
        "nobody@qnop.test" // unknown
      })
  void forgotPasswordIsAlwaysNoContent(String email) throws Exception {
    mockMvc
        .perform(
            post(FORGOT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
        .andExpect(status().isNoContent());
  }

  @Test
  void resetWithAnUnknownTokenIsRejected() throws Exception {
    mockMvc
        .perform(
            post(RESET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"does-not-exist\",\"newPassword\":\"New-Pass-9876!\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void resetWithAShortPasswordIsAValidationError() throws Exception {
    mockMvc
        .perform(
            post(RESET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"whatever\",\"newPassword\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("a deployment without self-service reset refuses instead of going quiet (#713)")
  void refusesWhereResetIsDisabled() throws Exception {
    settings.update(java.util.Map.of("auth.password_reset_enabled", "false"), null);

    // It used to answer 204 and do nothing, so the sender waited for a mail that
    // was never going to be sent. What is disclosed here is a property of the
    // instance, which /config publishes anyway — never anything about a person.
    mockMvc
        .perform(
            post(FORGOT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@qnop.test\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(jsonPath("$.auth.passwordResetEnabled").value(false));
  }

  @Test
  @DisplayName("with reset enabled the answer stays uniform, known address or not")
  void answerStaysUniformWhereResetIsEnabled() throws Exception {
    settings.update(java.util.Map.of("auth.password_reset_enabled", "true"), null);

    // The anti-enumeration property is the one thing #713 must not have broken:
    // both of these are 204, and a caller learns nothing from the difference.
    mockMvc
        .perform(
            post(FORGOT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@qnop.test\"}"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post(FORGOT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-at-all@qnop.test\"}"))
        .andExpect(status().isNoContent());
  }
}
