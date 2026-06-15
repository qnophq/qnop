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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.service.oidc.OidcProviderService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link ConfigController}. Proves the OpenAPI-first toolchain end-to-end: the
 * controller implements the generated {@code ServerConfigApi} interface, is mounted under the
 * {@code /api/v1} prefix from {@link ApiPathConfig}, and serializes the generated {@code
 * ServerConfigResponse} DTO (ADR-0021). No database, so no Testcontainers/Docker is needed.
 */
// QnopApplication lives in io.qnop.bootstrap (a sibling, not a parent, of this
// package — ADR-0004), so the slice cannot auto-discover the @SpringBootConfiguration.
// Configure the context explicitly with just the controller and the path-prefix config.
@WebMvcTest
@ContextConfiguration(classes = {ConfigController.class, ApiPathConfig.class})
class ConfigControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OidcProviderService oidcProviders;

  @BeforeEach
  void setUp() {
    // No providers configured: auth.oidcProviders should serialize as an empty list.
    when(oidcProviders.findAll()).thenReturn(List.of());
  }

  @Test
  @DisplayName("GET /api/v1/config returns the public Community configuration")
  void getConfig_returnsCommunityConfig() throws Exception {
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.edition").value("COMMUNITY"))
        .andExpect(jsonPath("$.version").exists())
        .andExpect(jsonPath("$.general.siteName").value("qnop"))
        .andExpect(jsonPath("$.auth.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.auth.oidcProviders").isArray())
        .andExpect(jsonPath("$.auth.oidcProviders").isEmpty())
        .andExpect(jsonPath("$.upload.maxDocumentSizeMb").value(50))
        .andExpect(jsonPath("$.supportedFormats[0]").value("PDF"));
  }

  @Test
  @DisplayName("the config endpoint is only reachable under the /api/v1 prefix")
  void getConfig_withoutVersionPrefix_isNotFound() throws Exception {
    mockMvc.perform(get("/config")).andExpect(status().isNotFound());
  }
}
