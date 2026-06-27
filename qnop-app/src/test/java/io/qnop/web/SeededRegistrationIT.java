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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Self-registration + email verification (issue #163). Self-registration is disabled in the
 * baseline ({@code auth.self_registration_enabled=false}), so {@code /register} is a disguised 404;
 * verify-email rejects unknown tokens. The enabled happy-path needs both a settings override and
 * the emailed token, so it is exercised at the service layer instead.
 */
class SeededRegistrationIT extends SeededIntegrationTest {

  private static final String REGISTER = "/api/v1/auth/register";
  private static final String VERIFY = "/api/v1/auth/verify-email";

  @Test
  void registrationIsADisguised404WhenDisabled() throws Exception {
    mockMvc
        .perform(
            post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"newbie\",\"email\":\"newbie@qnop.test\","
                        + "\"password\":\"New-Pass-9876!\",\"displayName\":\"New Bie\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void verifyEmailRejectsAnUnknownToken() throws Exception {
    mockMvc
        .perform(get(VERIFY).param("token", "does-not-exist"))
        .andExpect(status().isBadRequest());
  }
}
