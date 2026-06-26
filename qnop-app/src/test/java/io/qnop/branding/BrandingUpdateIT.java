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
package io.qnop.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.ApplicationAssetRepository;
import io.qnop.repository.UserRepository;
import io.qnop.testsupport.TestData;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end "update branding" flows driven by the shared {@code testdata/branding} fixtures (issue
 * #106): an admin uploads, replaces with a different format, removes (falling back to the factory
 * default), and the public {@code /config} reflects each slot's custom-vs-default source. Also
 * asserts the security-relevant edges — SVG is sanitized before it is ever served, and a non-image
 * is rejected by content sniffing. Requires Docker (Testcontainers).
 */
class BrandingUpdateIT extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;
  @Autowired ApplicationAssetRepository assets;

  private MockMvc mockMvc;
  private UUID userId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = "branding-update-" + UUID.randomUUID();
    userId = users.saveAndFlush(User.internal("Admin", tag + "@example.com", tag, "hash")).getId();
  }

  @AfterEach
  void tearDown() {
    assets.deleteAll(); // application_asset.uploaded_by does not cascade — clear before the user
    users.deleteById(userId);
  }

  private JwtRequestPostProcessor superadmin() {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private MockMultipartFile fixture(String name, String contentType, String fixturePath) {
    return new MockMultipartFile("file", name, contentType, TestData.bytes(fixturePath));
  }

  private void upload(String slot, MockMultipartFile file) throws Exception {
    mockMvc
        .perform(multipart("/api/v1/admin/branding/" + slot).file(file).with(superadmin()))
        .andExpect(status().isOk());
  }

  @Test
  void uploadsPngFixtureThenServesItWithEtag() throws Exception {
    upload("logo-light", fixture("logo.png", "image/png", "branding/logo-light.png"));

    mockMvc
        .perform(get("/api/v1/branding/logo-light"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().exists("ETag"));
  }

  @Test
  void replacingWithADifferentFormatChangesTheServedAssetAndEtag() throws Exception {
    upload("logomark", fixture("logo.png", "image/png", "branding/logo-light.png"));
    String pngEtag =
        mockMvc
            .perform(get("/api/v1/branding/logomark"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    upload("logomark", fixture("logomark.svg", "image/svg+xml", "branding/logomark.svg"));

    MvcResult svg =
        mockMvc
            .perform(get("/api/v1/branding/logomark"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/svg+xml"))
            .andReturn();
    assertThat(svg.getResponse().getHeader("ETag")).isNotNull().isNotEqualTo(pngEtag);
  }

  @Test
  void removingACustomAssetFallsBackToTheFactoryDefault() throws Exception {
    upload("logo-dark", fixture("logo.png", "image/png", "branding/logo-dark.png"));

    mockMvc
        .perform(delete("/api/v1/admin/branding/logo-dark").with(superadmin()))
        .andExpect(status().isNoContent());

    // Not gone — the slot serves the bundled factory default (SVG), never 404.
    mockMvc
        .perform(get("/api/v1/branding/logo-dark"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/svg+xml"));
  }

  @Test
  void sanitizesAnUnsafeSvgBeforeItIsEverServed() throws Exception {
    upload("logo-light", fixture("unsafe.svg", "image/svg+xml", "branding/unsafe.svg"));

    String served =
        mockMvc
            .perform(get("/api/v1/branding/logo-light"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/svg+xml"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(served).doesNotContain("<script", "onload", "javascript:");
  }

  @Test
  void rejectsANonImageByContentSniffing() throws Exception {
    // Declared image/png, but the bytes are plain text — the server sniffs and rejects it.
    mockMvc
        .perform(
            multipart("/api/v1/admin/branding/logo-light")
                .file(fixture("logo.png", "image/png", "branding/not-an-image.txt"))
                .with(superadmin()))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void configReflectsCustomThenDefaultSource() throws Exception {
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branding.logoLight.source").value("DEFAULT"));

    upload("logo-light", fixture("logo.png", "image/png", "branding/logo-light.png"));
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(jsonPath("$.branding.logoLight.source").value("CUSTOM"));

    mockMvc
        .perform(delete("/api/v1/admin/branding/logo-light").with(superadmin()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(jsonPath("$.branding.logoLight.source").value("DEFAULT"));
  }
}
