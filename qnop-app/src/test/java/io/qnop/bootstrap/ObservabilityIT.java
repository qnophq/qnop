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
package io.qnop.bootstrap;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Actuator observability wiring (issue #348): {@code /actuator/health} is public (probes), the
 * Prometheus scrape is admin-only, and it publishes the job-queue depth gauges.
 */
class ObservabilityIT extends SeededIntegrationTest {

  private MockHttpServletRequestBuilder asUser(
      MockHttpServletRequestBuilder builder, String token) {
    return builder.header("Authorization", "Bearer " + token);
  }

  @Test
  void healthIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void prometheusIsNotReadableAnonymously() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void prometheusIsForbiddenToNonAdmins() throws Exception {
    mockMvc
        .perform(asUser(get("/actuator/prometheus"), token(MEMBER_ID)))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminScrapeExposesTheJobQueueDepthGauge() throws Exception {
    mockMvc
        .perform(asUser(get("/actuator/prometheus"), token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("qnop_jobs")));
  }
}
