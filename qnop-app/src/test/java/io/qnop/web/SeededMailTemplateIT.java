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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Mail templates (issues #140/#141): read, placeholder validation, preview rendering, and the
 * SMTP-less test-send. The happy-path override (PUT) and reset (DELETE) mutate the migration-seeded
 * {@code mail_template} table, which {@code clean.sql} does not restore, so they are exercised at
 * the service layer instead; here we cover only the rejected (no-commit) PUT and read/preview
 * paths.
 */
class SeededMailTemplateIT extends SeededIntegrationTest {

  @org.springframework.beans.factory.annotation.Autowired
  private io.qnop.service.ApplicationSettingsService settings;

  private static final String TEMPLATES = "/api/v1/admin/email/templates";
  private static final String RESET_KEY = "auth.password_reset";

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(ADMIN_ID));
  }

  @Test
  void listsTemplatesWithTheirPlaceholders() throws Exception {
    mockMvc
        .perform(asAdmin(get(TEMPLATES)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.templates").isArray())
        .andExpect(jsonPath("$.templates[?(@.key=='" + RESET_KEY + "')]").exists());
  }

  @Test
  void getsASingleTemplate() throws Exception {
    mockMvc
        .perform(asAdmin(get(TEMPLATES + "/" + RESET_KEY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value(RESET_KEY))
        .andExpect(jsonPath("$.subject").isNotEmpty())
        .andExpect(jsonPath("$.placeholders").value(hasItem("actionUrl")));
  }

  @Test
  void getsAnUnknownTemplateAs404() throws Exception {
    mockMvc.perform(asAdmin(get(TEMPLATES + "/does.not.exist"))).andExpect(status().isNotFound());
  }

  @Test
  void previewsATemplateWithSampleVariables() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(TEMPLATES + "/" + RESET_KEY + "/preview"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variables\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").isNotEmpty())
        .andExpect(jsonPath("$.bodyPlain").isNotEmpty())
        .andExpect(jsonPath("$.sampleVars").exists());
  }

  @Test
  void rejectsAPreviewDraftWithAnUnknownPlaceholder() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(TEMPLATES + "/" + RESET_KEY + "/preview"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"subject\":\"Hi {{bogus}}\",\"bodyPlain\":\"Body {{bogus}}\","
                        + "\"variables\":{}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsAnUpdateWithAnUnknownPlaceholder() throws Exception {
    mockMvc
        .perform(
            asAdmin(put(TEMPLATES + "/" + RESET_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"locale\":\"en\",\"subject\":\"Reset {{bogus}}\","
                        + "\"bodyPlain\":\"Click {{actionUrl}}\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sendingATestEmailIsSkippedWhenSmtpIsNotConfigured() throws Exception {
    // The seed points SMTP at Mailpit (issue #401) — this test owns the
    // skip path, so it switches the master toggle off itself.
    settings.update(java.util.Map.of("smtp.enabled", "false"), null);

    mockMvc
        .perform(
            asAdmin(post("/api/v1/admin/email/test"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipient\":\"test@qnop.test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SKIPPED"));
  }
}
