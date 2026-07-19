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
package io.qnop.scheduler;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The admin scheduler dashboard over the wire (issue #524, ADR-0045): the ADMIN role gate (a MEMBER
 * is forbidden), listing the fixed catalogue, enabling/disabling and the dry-run capability guard,
 * unknown-job 404s, and a run-now that reports its outcome on the returned job.
 */
class SchedulerApiIT extends SeededIntegrationTest {

  private static final String SCHEDULER = "/api/v1/admin/scheduler";
  private static final String REFRESH_TOKEN_SWEEP = "refreshTokenSweep";
  private static final String STORAGE_ORPHAN_REAPER = "storageOrphanReaper";

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  @Test
  @DisplayName("a MEMBER is forbidden — the dashboard is ADMIN-only")
  void memberForbidden() throws Exception {
    mockMvc.perform(as(get(SCHEDULER), MEMBER_ID)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("an unauthenticated caller is unauthorized")
  void requiresAuth() throws Exception {
    mockMvc.perform(get(SCHEDULER)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("an ADMIN lists the full catalogue, including the dry-run-capable reaper")
  void adminListsCatalogue() throws Exception {
    mockMvc
        .perform(as(get(SCHEDULER), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(5)))
        .andExpect(jsonPath("$.items[*].jobId", hasItem(STORAGE_ORPHAN_REAPER)))
        .andExpect(jsonPath("$.items[*].jobId", hasItem(REFRESH_TOKEN_SWEEP)));
  }

  @Test
  @DisplayName("an ADMIN can disable a scheduled sweep")
  void adminDisablesSweep() throws Exception {
    mockMvc
        .perform(
            as(patch(SCHEDULER + "/" + REFRESH_TOKEN_SWEEP), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(REFRESH_TOKEN_SWEEP))
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  @DisplayName("dry-run is rejected for a job that does not support it (400)")
  void dryRunRejectedForTokenSweep() throws Exception {
    mockMvc
        .perform(
            as(patch(SCHEDULER + "/" + REFRESH_TOKEN_SWEEP), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DRY_RUN_NOT_SUPPORTED"));
  }

  @Test
  @DisplayName("dry-run is accepted for the storage reaper")
  void dryRunAcceptedForReaper() throws Exception {
    mockMvc
        .perform(
            as(patch(SCHEDULER + "/" + STORAGE_ORPHAN_REAPER), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.supportsDryRun").value(true));
  }

  @Test
  @DisplayName("an unknown job id is a 404 on update")
  void unknownJobUpdate404() throws Exception {
    mockMvc
        .perform(
            as(patch(SCHEDULER + "/nope"), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SCHEDULER_JOB_NOT_FOUND"));
  }

  @Test
  @DisplayName("run-now executes the sweep and reports a SUCCESS outcome on the returned job")
  void runNowReportsOutcome() throws Exception {
    mockMvc
        .perform(as(post(SCHEDULER + "/" + REFRESH_TOKEN_SWEEP + "/run"), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(REFRESH_TOKEN_SWEEP))
        .andExpect(jsonPath("$.lastOutcome").value("SUCCESS"))
        .andExpect(jsonPath("$.lastTrigger").value("MANUAL"));
  }

  @Test
  @DisplayName("run-now on an unknown job id is a 404")
  void runNowUnknown404() throws Exception {
    mockMvc.perform(as(post(SCHEDULER + "/nope/run"), ADMIN_ID)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a run-now surfaces on the SYSTEM audit stream for an AUDITOR")
  void runNowAudited() throws Exception {
    mockMvc
        .perform(as(post(SCHEDULER + "/" + REFRESH_TOKEN_SWEEP + "/run"), ADMIN_ID))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            as(get("/api/v1/audit/events").param("eventType", "scheduler.job.run"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].scope").value("SYSTEM"))
        .andExpect(jsonPath("$.items[0].actorId").value(ADMIN_ID.toString()));
  }
}
