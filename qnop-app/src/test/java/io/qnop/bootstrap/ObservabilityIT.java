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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Actuator observability wiring (issue #348): {@code /actuator/health} is public (probes), the
 * Prometheus scrape is admin-only, and it publishes the job-queue depth gauges. Health details
 * (component status, job-queue counts) are reserved to administrators (issue #923): any other
 * caller, signed in or not, sees the aggregate status only.
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
  void anonymousHealthShowsStatusOnly() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").exists())
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void memberHealthShowsStatusOnly() throws Exception {
    mockMvc
        .perform(asUser(get("/actuator/health"), token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").exists())
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void adminHealthShowsComponentDetails() throws Exception {
    mockMvc
        .perform(asUser(get("/actuator/health"), token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.db").exists());
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

  @Test
  @DisplayName("the framework series the dashboard and alert rules use exist in a real scrape")
  void scrapeHasTheFrameworkSeriesTheDashboardUses() throws Exception {
    // A request is recorded once it is answered, so the scrape cannot count on itself.
    mockMvc.perform(get("/api/v1/config")).andExpect(status().isOk());
    String scrape =
        mockMvc
            .perform(asUser(get("/actuator/prometheus"), token(ADMIN_ID)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // A framework upgrade that renames one of these would otherwise empty a panel silently.
    assertThat(MetricCatalogueTest.FRAMEWORK_SERIES)
        .allSatisfy(
            series ->
                assertThat(scrape.lines().anyMatch(line -> line.startsWith(series)))
                    .as("the scrape has %s", series)
                    .isTrue());
  }

  @Test
  @DisplayName("the failure series the alert rules watch are in the scrape before any failure")
  void failureSeriesArePublishedFromTheStart() throws Exception {
    String scrape =
        mockMvc
            .perform(asUser(get("/actuator/prometheus"), token(ADMIN_ID)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Issue #984: a series born at its first event hides that event, and a whole burst within
    // one scrape interval, from increase(). Each of these must already be there, whatever this
    // context has or has not done. They span the meter classes and every way a series is
    // declared: by its meter, by the job queue's handlers, by the mail catalogue, and by the
    // rate limits of the filters and of the identifier limiter.
    assertThat(
            List.of(
                "qnop_auth_refresh_total{application=\"qnop\",outcome=\"reused\"}",
                "qnop_auth_login_total{application=\"qnop\",method=\"local\",outcome=\"rejected\"}",
                "qnop_ratelimit_rejected_total{application=\"qnop\",scope=\"login\"}",
                "qnop_ratelimit_rejected_total{application=\"qnop\",scope=\"login-account\"}",
                "qnop_storage_operation_seconds_count{application=\"qnop\",operation=\"put\",outcome=\"error\"}",
                "qnop_mail_send_seconds_count{application=\"qnop\",outcome=\"failed\",template=\"adhoc\"}",
                "qnop_mail_send_seconds_count{application=\"qnop\",outcome=\"failed\",template=\"password_reset\"}",
                "qnop_job_execution_seconds_count{application=\"qnop\",outcome=\"failed\",type=\"document.extract\"}",
                "qnop_document_extraction_seconds_count{application=\"qnop\",format=\"pdf\",outcome=\"error\"}",
                "qnop_office_conversion_seconds_count{application=\"qnop\",outcome=\"failure\"}"))
        .allSatisfy(
            series ->
                assertThat(scrape.lines().anyMatch(line -> line.startsWith(series + " ")))
                    .as("the scrape has %s", series)
                    .isTrue());
  }

  @Test
  @DisplayName("every qnop series of a real scrape is one the catalogue test knows")
  void noMeterEscapesTheCatalogueTest() throws Exception {
    String scrape =
        mockMvc
            .perform(asUser(get("/actuator/prometheus"), token(ADMIN_ID)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // PublishedMeters is a list kept by hand. A gauge binder or meter that is missing from it
    // would escape the dashboard, catalogue and tag checks; here it shows up in the scrape.
    assertThat(
            MetricCatalogueTest.seriesNamesOf(
                scrape.lines().filter(line -> line.startsWith("qnop_")).toList()))
        .isNotEmpty()
        .isSubsetOf(MetricCatalogueTest.publishableSeries());
  }
}
