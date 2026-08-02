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
package io.qnop.limits;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * The quotas as a client meets them (issue #673).
 *
 * <p>Every limit is set to 1 while the seeded dataset already holds more than that, so this
 * exercises the case an operator actually reaches: a deployment that is already over its quota
 * because somebody lowered it. Nothing existing is disturbed and every creation is refused — with
 * 409 and a code naming which ceiling was hit.
 */
@TestPropertySource(
    properties = {
      "qnop.limits.max-users=1",
      "qnop.limits.max-teams=1",
      "qnop.limits.max-team-members=1",
      "qnop.limits.max-active-reviews=1"
    })
class InstanceLimitIT extends SeededIntegrationTest {

  @org.springframework.beans.factory.annotation.Autowired
  private io.qnop.service.ApplicationSettingsService settings;

  @org.springframework.beans.factory.annotation.Autowired
  private io.qnop.repository.UserRepository users;

  @org.junit.jupiter.api.AfterEach
  void restoreSelfRegistration() {
    settings.update(
        java.util.Map.of(
            io.qnop.service.ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED.getKey(), "false"),
        null);
  }

  @Test
  @DisplayName("an administrator cannot add a user beyond the quota")
  void refusesNewUsers() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/users")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Nina New\",\"username\":\"nina\","
                        + "\"email\":\"nina@example.com\",\"role\":\"MEMBER\"}"))
        // 409 and not 403: the administrator is allowed to do this, the
        // deployment simply has no room.
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_LIMIT_EXCEEDED"))
        // The ceiling belongs in the message — otherwise the reader cannot tell
        // how much they would have to free.
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1")));
  }

  @Test
  @DisplayName("self-registration is refused the same way")
  void refusesSelfRegistration() throws Exception {
    settings.update(
        java.util.Map.of(
            io.qnop.service.ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED.getKey(), "true"),
        null);

    // The path a stranger walks. It must not be the way around the quota.
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"walkin\",\"email\":\"walkin@example.com\","
                        + "\"password\":\"Str0ng-Pass-9876!\",\"displayName\":\"Walk In\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_LIMIT_EXCEEDED"));
  }

  @Test
  @DisplayName("teams stop at their quota too")
  void refusesNewTeams() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/teams")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Team Gamma\",\"leadUserId\":\"" + MEMBER_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TEAM_LIMIT_EXCEEDED"));
  }

  @Test
  @DisplayName("the review quota admits one and refuses the next")
  void refusesNewReviews() throws Exception {
    // Both sides of the boundary in one test: the seeded dataset carries no open
    // reviews, so the first upload has to succeed — a quota that refused from
    // empty would pass a one-sided assertion just as happily.
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", pdf()))
                .param("title", "Within the quota")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", pdf()))
                .param("title", "One too many")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACTIVE_REVIEW_LIMIT_EXCEEDED"));
  }

  @Test
  @DisplayName("an administrator can read the quotas and what they hold")
  void reportsUsage() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/limits").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users.maximum").value(1))
        // Seeded users outnumber the quota — which is exactly the state an
        // operator sees after lowering one, and it must be readable rather than
        // an error.
        .andExpect(jsonPath("$.users.used").value(org.hamcrest.Matchers.greaterThan(1)))
        .andExpect(jsonPath("$.teams.maximum").value(1))
        .andExpect(jsonPath("$.activeReviews.maximum").value(1));
  }

  @Test
  @DisplayName("reading the quotas is for administrators")
  void usageIsAdminOnly() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/limits").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isForbidden());
  }

  /** The smallest thing the ingest pipeline accepts as a document. */
  private static byte[] pdf() throws IOException {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage(PDRectangle.LETTER));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  @Test
  @DisplayName("the user list carries its own capacity, so the screen can refuse first")
  void userListReportsSeats() throws Exception {
    // Straight from the list the admin screen already loads (issue #687): it
    // cannot come from /admin/limits, which belongs to the configuration screen
    // a deployment may withhold (#683).
    mockMvc
        .perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seatLimit").value(1))
        // Every account counts, disabled ones included — the seed holds more
        // than one, which is exactly why the next creation is refused.
        .andExpect(jsonPath("$.seatsUsed").value(org.hamcrest.Matchers.greaterThan(1)));
  }

  @Test
  @DisplayName("a disabled account still occupies its seat")
  void disabledAccountsStillCount() throws Exception {
    // The bug this replaced (issue #687): with the enabled-only count, disabling
    // an account freed a seat, so a deployment configured for N could hold N+1.
    long before = users.count();
    users
        .findById(MEMBER_ID)
        .ifPresent(
            user -> {
              user.setEnabled(false);
              users.save(user);
            });

    mockMvc
        .perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(jsonPath("$.seatsUsed").value((int) before));
  }
}
