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
import io.qnop.repository.UserRepository;
import io.qnop.service.avatar.AvatarLimits;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the profile-avatar endpoints (issue #117) through the real security chain: a user
 * uploads their own picture and an admin uploads for any user; the read path is public and serves
 * an ETag/304; validation rejects bad types/sizes; removal restores the absent (404) state; and the
 * admin user DTO exposes the avatar URL. Requires Docker (Testcontainers).
 */
@Transactional
class AvatarControllerIT extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;

  private MockMvc mockMvc;
  private UUID userId;
  private UUID adminId;
  private byte[] png;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = "avatar-" + UUID.randomUUID();
    userId = users.saveAndFlush(User.internal("Member", tag + "@example.com", tag, "hash")).getId();
    String adminTag = "avatar-admin-" + UUID.randomUUID();
    adminId =
        users
            .saveAndFlush(User.internal("Admin", adminTag + "@example.com", adminTag, "hash"))
            .getId();
    BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    png = out.toByteArray();
  }

  private JwtRequestPostProcessor self() {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"));
  }

  private JwtRequestPostProcessor admin() {
    return jwt()
        .jwt(j -> j.subject(adminId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private MockMultipartFile file(String name, String contentType, byte[] bytes) {
    return new MockMultipartFile("file", name, contentType, bytes);
  }

  @Test
  void selfUploadThenPubliclyServedWithEtagAnd304() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/users/me/avatar").file(file("a.png", "image/png", png)).with(self()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.containsString(userId.toString())));

    MvcResult served =
        mockMvc
            .perform(get("/api/v1/users/" + userId + "/avatar"))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andReturn();

    String etag = served.getResponse().getHeader("ETag");
    mockMvc
        .perform(get("/api/v1/users/" + userId + "/avatar").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void adminUploadsForAnotherUser() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/users/" + userId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(admin()))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/users/" + userId + "/avatar")).andExpect(status().isOk());
  }

  @Test
  void publicGetMissingAvatarReturns404() throws Exception {
    mockMvc.perform(get("/api/v1/users/" + userId + "/avatar")).andExpect(status().isNotFound());
  }

  @Test
  void selfUploadRejectsUnsupportedType() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/users/me/avatar")
                .file(file("note.txt", "text/plain", new byte[] {1, 2, 3, 4, 5, 6, 7, 8}))
                .with(self()))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void selfUploadRejectsOversizedPayload() throws Exception {
    byte[] tooBig = new byte[(int) AvatarLimits.MAX_SIZE_BYTES + 1];
    mockMvc
        .perform(
            multipart("/api/v1/users/me/avatar")
                .file(file("big.png", "image/png", tooBig))
                .with(self()))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void memberCannotUploadViaAdminPath() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/users/" + userId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(self()))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousUploadIsUnauthorized() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/users/me/avatar").file(file("a.png", "image/png", png)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void selfRemoveRestoresAbsentState() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/users/me/avatar").file(file("a.png", "image/png", png)).with(self()))
        .andExpect(status().isOk());

    mockMvc
        .perform(delete("/api/v1/users/me/avatar").with(self()))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/v1/users/" + userId + "/avatar")).andExpect(status().isNotFound());
  }

  @Test
  void adminUserDetailExposesAvatarUrl() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/users/" + userId + "/avatar")
                .file(file("a.png", "image/png", png))
                .with(admin()))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/admin/users/" + userId).with(admin()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.containsString("/avatar?v=")));
  }
}
