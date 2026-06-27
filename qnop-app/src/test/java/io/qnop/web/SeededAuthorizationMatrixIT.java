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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Authorization matrix for the ADMIN-only API surface (issue #163), exercised against the seeded
 * dataset: every admin endpoint must reject an anonymous caller (401) and a non-admin token (403),
 * and admit an admin token (200). This pins the {@code /api/v1/admin/**} ⇒ ADMIN rule (issue #98).
 */
class SeededAuthorizationMatrixIT extends SeededIntegrationTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/admin/users",
        "/api/v1/admin/settings",
        "/api/v1/admin/oidc-providers",
        "/api/v1/admin/email/templates"
      })
  void anonymousIsUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/admin/users",
        "/api/v1/admin/settings",
        "/api/v1/admin/oidc-providers",
        "/api/v1/admin/email/templates"
      })
  void memberIsForbidden(String path) throws Exception {
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/admin/users",
        "/api/v1/admin/settings",
        "/api/v1/admin/oidc-providers",
        "/api/v1/admin/email/templates"
      })
  void auditorIsForbidden(String path) throws Exception {
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + token(AUDITOR_ID)))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/admin/users",
        "/api/v1/admin/settings",
        "/api/v1/admin/oidc-providers",
        "/api/v1/admin/email/templates"
      })
  void adminIsAllowed(String path) throws Exception {
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk());
  }
}
