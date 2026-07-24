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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the {@code /api/v1/users/me/settings} endpoints (issue #22) through the real security
 * filter chain. The acting user is a JWT principal whose subject is the user's UUID; an anonymous
 * caller gets 401 and a non-UUID (API-key) subject gets 403. MockMvc is built with {@code
 * springSecurity()} so the test JWT reaches the filter chain. Requires Docker.
 */
class UserSettingsControllerIT extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;

  private MockMvc mockMvc;
  private UUID userId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    userId = users.saveAndFlush(User.internal("Me", "me@example.com", "me", "hash")).getId();
  }

  @AfterEach
  void tearDown() {
    users.deleteById(userId); // ON DELETE CASCADE removes any user_setting rows
  }

  @Test
  void returnsSettingsForAuthenticatedUser() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/me/settings").with(jwt().jwt(j -> j.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings").isArray())
        // theme, preferred_language, timezone, email_review_notifications, email_mentions (#462).
        .andExpect(jsonPath("$.settings.length()").value(5));
  }

  @Test
  void unauthorizedForAnonymous() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/settings")).andExpect(status().isUnauthorized());
  }

  @Test
  void forbiddenForNonUserPrincipal() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me/settings").with(jwt().jwt(j -> j.subject("api-key-123"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void patchUpdatesSetting() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/users/me/settings")
                .with(jwt().jwt(j -> j.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"theme\":\"dark\"}}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.settings[?(@.key=='theme')].value")
                .value(org.hamcrest.Matchers.hasItem("dark")));
  }

  @Test
  void patchRejectsInvalidValue() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/users/me/settings")
                .with(jwt().jwt(j -> j.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"theme\":\"neon\"}}"))
        .andExpect(status().isBadRequest());
  }
}
