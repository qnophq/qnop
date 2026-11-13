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
package io.qnop.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The scrape-token setting (issue #962). The start-up cases bind through Spring, because "refuses
 * to start" is a claim about the application, not about the record.
 */
class MetricsScrapePropertiesTest {

  private static final String SHORT = "a".repeat(31);
  private static final String LONG_ENOUGH = "a".repeat(32);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Configuration
  @EnableConfigurationProperties(MetricsScrapeProperties.class)
  static class TestConfig {}

  @Test
  @DisplayName("starts with the token unset or blank, and treats both as off")
  void blankMeansOff() {
    runner.run(
        context ->
            assertThat(context.getBean(MetricsScrapeProperties.class).scrapeToken()).isNull());
    runner
        .withPropertyValues("qnop.metrics.scrape-token=")
        .run(
            context ->
                assertThat(context.getBean(MetricsScrapeProperties.class).scrapeToken()).isNull());
  }

  @Test
  @DisplayName("starts with a token of 32 characters")
  void acceptsALongEnoughToken() {
    runner
        .withPropertyValues("qnop.metrics.scrape-token=" + LONG_ENOUGH)
        .run(
            context ->
                assertThat(context.getBean(MetricsScrapeProperties.class).scrapeToken())
                    .isEqualTo(LONG_ENOUGH));
  }

  @Test
  @DisplayName("refuses to start with a token shorter than 32 characters")
  void refusesAShortToken() {
    runner
        .withPropertyValues("qnop.metrics.scrape-token=" + SHORT)
        .run(
            context -> {
              assertThat(context).hasFailed();
              // Boot's failure report prints what the exception chain carries; the token must
              // not be in it.
              for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(SHORT);
              }
            });
  }

  @Test
  @DisplayName("refuses a token containing whitespace, which could never match a request")
  void refusesWhitespace() {
    assertThatThrownBy(() -> new MetricsScrapeProperties(LONG_ENOUGH + "\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MetricsScrapeProperties("a".repeat(20) + " " + "a".repeat(20)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("never repeats the rejected token in its error message")
  void errorDoesNotRevealTheToken() {
    assertThatThrownBy(() -> new MetricsScrapeProperties(SHORT))
        .hasMessageNotContaining(SHORT)
        .hasMessageContaining("qnop.metrics.scrape-token");
  }
}
