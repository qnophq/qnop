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
package io.qnop.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the admin settings endpoints (issue #16, ADR-0025) end-to-end through the real security
 * filter chain: the {@code /api/v1/admin/**} namespace requires {@code ADMIN}, the GET returns the
 * (redacted) settings, and PATCH validates and applies updates.
 *
 * <p>MockMvc is built explicitly with {@code springSecurity()} so {@code @WithMockUser} is honored
 * by the filter chain (Spring Boot 4's {@code @AutoConfigureMockMvc} does not apply it on its own
 * here). Requires Docker.
 */
class AdminSettingsControllerIT extends io.qnop.testsupport.SeededIntegrationTest {

  /**
   * A principal that is a real, seeded user id.
   *
   * <p>The endpoint records who changed a setting (issue #718), and {@code audit_event.actor_id} is
   * a foreign key — so an invented id fails the insert rather than being written and ignored.
   * {@code @WithMockUser}'s default principal ("user") is not a UUID at all, and this class now
   * extends the seeded base so the id below exists.
   */
  private static final String ADMIN_PRINCIPAL = "a0000000-0000-0000-0000-000000000001";

  @Autowired WebApplicationContext context;
  @Autowired ApplicationSettingsService settings;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @AfterEach
  void resetMutatedKeys() {
    settings.update(
        Map.of(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB.getKey(), "25"), null);
  }

  @Test
  @WithMockUser(username = ADMIN_PRINCIPAL, roles = "ADMIN")
  void getReturnsSettingsForAdmin() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings").isArray())
        // Every registry key is offered, so the count moves whenever the registry
        // does — asserted against the registry rather than a number that has to be
        // remembered (the banner keys of issue #664 were the latest to move it).
        .andExpect(jsonPath("$.settings.length()").value(ApplicationSettingKey.values().length));
  }

  @Test
  @WithMockUser(username = ADMIN_PRINCIPAL, roles = "MEMBER")
  void getForbiddenForNonAdmin() throws Exception {
    mockMvc.perform(get("/api/v1/admin/settings")).andExpect(status().isForbidden());
  }

  @Test
  void getUnauthorizedForAnonymous() throws Exception {
    mockMvc.perform(get("/api/v1/admin/settings")).andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = ADMIN_PRINCIPAL, roles = "ADMIN")
  void patchUpdatesSetting() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"upload.document_max_file_size_mb\":\"40\"}}"))
        .andExpect(status().isOk());

    assertThat(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
        .isEqualTo(40);
  }

  @Test
  @WithMockUser(username = ADMIN_PRINCIPAL, roles = "ADMIN")
  void patchRejectsTypeInvalidValue() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"upload.document_max_file_size_mb\":\"abc\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("upload.document_max_file_size_mb"))
        .andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty());
  }

  @Test
  @WithMockUser(username = ADMIN_PRINCIPAL, roles = "ADMIN")
  void patchReportsEveryInvalidFieldAtOnce() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"smtp.port\":\"70000\",\"smtp.from\":\"nope\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.length()").value(2));
  }
}
