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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.repository.ApplicationAssetRepository;
import io.qnop.repository.UserRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
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
 * Verifies the branding endpoints (issue #23) through the real security filter chain: superadmin
 * upload/delete, public ETag/304 serving, 404 for unknown/absent slots, and 403 for non-superadmin.
 * Requires Docker.
 */
class BrandingControllerIT extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;
  @Autowired ApplicationAssetRepository assets;

  private MockMvc mockMvc;
  private UUID userId;
  private byte[] png;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = "branding-" + UUID.randomUUID();
    userId = users.saveAndFlush(User.internal("Admin", tag + "@example.com", tag, "hash")).getId();
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    png = out.toByteArray();
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

  private MockMultipartFile logo() {
    return new MockMultipartFile("file", "logo.png", "image/png", png);
  }

  @Test
  void superadminUploadsThenAssetIsPubliclyServedWithEtag() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/admin/branding/logo-light").file(logo()).with(superadmin()))
        .andExpect(status().isOk());

    MvcResult served =
        mockMvc
            .perform(get("/api/v1/branding/logo-light"))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andReturn();

    String etag = served.getResponse().getHeader("ETag");
    mockMvc
        .perform(get("/api/v1/branding/logo-light").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void publicGetUnknownSlotReturns404() throws Exception {
    mockMvc.perform(get("/api/v1/branding/does-not-exist")).andExpect(status().isNotFound());
  }

  @Test
  void uploadForbiddenForNonSuperadmin() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/branding/logo-light")
                .file(logo())
                .with(jwt().jwt(j -> j.subject(userId.toString()))))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteFallsBackToFactoryDefault() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/admin/branding/logomark").file(logo()).with(superadmin()))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/branding/logomark"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"));

    mockMvc
        .perform(delete("/api/v1/admin/branding/logomark").with(superadmin()))
        .andExpect(status().isNoContent());

    // After delete the slot serves the bundled factory default (SVG), not a 404.
    mockMvc
        .perform(get("/api/v1/branding/logomark"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/svg+xml"));
  }

  @Test
  void emptySlotServesFactoryDefault() throws Exception {
    mockMvc
        .perform(get("/api/v1/branding/logo-light"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/svg+xml"))
        .andExpect(header().exists("ETag"));
  }
}
