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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Admin user create/update/delete and the self-protection guards (issue #163). {@code LAST_ADMIN}
 * is deliberately not asserted here: the acting principal is itself an enabled admin, so the
 * self-guards ({@code SELF_LOCKOUT}/{@code SELF_DELETE}) always preempt it via the API — the
 * last-admin path is covered at the service layer.
 */
class SeededAdminUserCrudIT extends SeededIntegrationTest {

  private static final String USERS = "/api/v1/admin/users";

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asAdmin(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(ADMIN_ID));
  }

  @Test
  void createsAUserWithAnInitialPassword() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(USERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"New User\",\"username\":\"newuser\","
                        + "\"email\":\"newuser@qnop.test\",\"role\":\"MEMBER\","
                        + "\"initialPassword\":\"Init-Pass-1234!\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("newuser"))
        .andExpect(jsonPath("$.email").value("newuser@qnop.test"))
        .andExpect(jsonPath("$.role").value("MEMBER"))
        .andExpect(jsonPath("$.source").value("INTERNAL"))
        .andExpect(jsonPath("$.enabled").value(true));

    // The freshly created account can authenticate with its initial password.
    org.junit.jupiter.api.Assertions.assertEquals(
        200, login("newuser", "Init-Pass-1234!").getResponse().getStatus());
  }

  @Test
  void createsAnInvitedUserWithoutAPassword() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(USERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Invited\",\"username\":\"invited\","
                        + "\"email\":\"invited@qnop.test\",\"role\":\"AUDITOR\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("AUDITOR"))
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void rejectsADuplicateEmail() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(USERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Clash\",\"username\":\"clash\","
                        + "\"email\":\"admin@qnop.test\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
  }

  @Test
  void rejectsADuplicateUsername() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(USERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Clash\",\"username\":\"admin\","
                        + "\"email\":\"clash@qnop.test\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
  }

  @Test
  void rejectsATooShortUsernameWithAValidationError() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(USERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Short\",\"username\":\"ab\","
                        + "\"email\":\"short@qnop.test\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void updatesDisplayNameAndRole() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(USERS + "/" + MEMBER2_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Renamed Member\",\"role\":\"AUDITOR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Renamed Member"))
        .andExpect(jsonPath("$.role").value("AUDITOR"));
  }

  @Test
  void rejectsChangingOwnRole() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(USERS + "/" + ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MEMBER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
  }

  @Test
  void rejectsDisablingOwnAccount() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(USERS + "/" + ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
  }

  @Test
  void updatingAnUnknownUserIsNotFound() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(USERS + "/a0000000-0000-0000-0000-0000000000ff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Ghost\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletesAUser() throws Exception {
    mockMvc.perform(asAdmin(delete(USERS + "/" + MEMBER2_ID))).andExpect(status().isNoContent());
    mockMvc.perform(asAdmin(get(USERS + "/" + MEMBER2_ID))).andExpect(status().isNotFound());
  }

  @Test
  void rejectsDeletingOwnAccount() throws Exception {
    mockMvc
        .perform(asAdmin(delete(USERS + "/" + ADMIN_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_DELETE"));
  }

  @Test
  void deletingAnUnknownUserIsNotFound() throws Exception {
    mockMvc
        .perform(asAdmin(delete(USERS + "/a0000000-0000-0000-0000-0000000000ff")))
        .andExpect(status().isNotFound());
  }
}
