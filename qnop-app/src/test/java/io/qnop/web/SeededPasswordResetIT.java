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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
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
}
