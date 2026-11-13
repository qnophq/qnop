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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The long-lived scrape token (issue #962, ADR-0065): it opens {@code GET /actuator/prometheus} for
 * an unattended Prometheus and nothing else, and the scrape it returns carries the platform meters
 * the dashboard is built on.
 */
@TestPropertySource(properties = "qnop.metrics.scrape-token=" + MetricsScrapeTokenIT.TOKEN)
class MetricsScrapeTokenIT extends SeededIntegrationTest {

  static final String TOKEN = "it-scrape-token-0123456789-0123456789-abcdef";

  private static MockHttpServletRequestBuilder withScrapeToken(
      MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + TOKEN);
  }

  @Test
  @DisplayName("the scrape token reads the scrape")
  void tokenReadsTheScrape() throws Exception {
    mockMvc
        .perform(withScrapeToken(get("/actuator/prometheus")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("qnop_jobs")));
  }

  @Test
  @DisplayName("a wrong token is refused like any other invalid bearer token")
  void wrongTokenIsRefused() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN + "x"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("an admin still reads the scrape with an access token")
  void adminAccessTokenStillWorks() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("the scrape token opens nothing else")
  void tokenOpensNothingElse() throws Exception {
    mockMvc
        .perform(withScrapeToken(get("/api/v1/admin/users")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(withScrapeToken(get("/api/v1/users/me"))).andExpect(status().isUnauthorized());
    mockMvc
        .perform(withScrapeToken(post("/actuator/prometheus")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the scrape token is not an admin: health details stay hidden")
  void tokenDoesNotRevealHealthDetails() throws Exception {
    mockMvc
        .perform(withScrapeToken(get("/actuator/health")))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  @DisplayName("the admin configuration page shows that a token is set, never the token")
  void tokenIsRedactedOnTheConfigurationPage() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/configuration").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("qnop.metrics.scrape-token")))
        .andExpect(content().string(not(containsString(TOKEN))));
  }

  @Test
  @DisplayName("the scrape carries the build, the common tag and the platform meters")
  void scrapeCarriesThePlatformMeters() throws Exception {
    mockMvc.perform(get("/api/v1/config")).andExpect(status().isOk());

    mockMvc
        .perform(withScrapeToken(get("/actuator/prometheus")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("application=\"qnop\"")))
        .andExpect(content().string(containsString("qnop_info{")))
        .andExpect(content().string(containsString("edition=\"community\"")))
        // Buckets, not just count and sum: a percentile across instances needs them.
        .andExpect(content().string(containsString("http_server_requests_seconds_bucket{")))
        .andExpect(content().string(containsString("le=\"0.25\"")))
        .andExpect(content().string(containsString("uri=\"/api/v1/config\"")))
        .andExpect(content().string(containsString("hikaricp_connections_active")))
        .andExpect(content().string(containsString("tomcat_threads_busy_threads")))
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("jvm_gc_")))
        .andExpect(content().string(containsString("process_cpu_usage")))
        .andExpect(content().string(containsString("logback_events_total")));
  }

  @Test
  @DisplayName("the scrape leaves out the framework meters that are switched off as noise")
  void scrapeLeavesOutTheNoise() throws Exception {
    mockMvc.perform(get("/api/v1/config")).andExpect(status().isOk());

    mockMvc
        .perform(withScrapeToken(get("/actuator/prometheus")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("spring_security_"))))
        .andExpect(content().string(not(containsString("spring_data_repository_"))))
        .andExpect(content().string(not(containsString("http_server_requests_active"))));
  }
}
