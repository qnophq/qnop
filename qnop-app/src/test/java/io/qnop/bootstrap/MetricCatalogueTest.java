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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the dashboard, the alert rules and the documentation to what the application really
 * publishes (issue #962, ADR-0065).
 *
 * <p>A panel or an alert on a series that does not exist is worse than none: it shows "no data",
 * which reads as "nothing is wrong". Renaming a meter without touching the dashboard, the rules and
 * the catalogue therefore fails here, as does a tag that is not on the list of the ones a meter may
 * carry.
 */
class MetricCatalogueTest {

  private static final Path OBSERVABILITY = Path.of("..", "deploy", "observability");
  private static final Path DASHBOARD =
      OBSERVABILITY.resolve(Path.of("grafana", "dashboards", "qnop-overview.json"));
  private static final Path ALERTS = OBSERVABILITY.resolve("alerts.yml");
  private static final Path CATALOGUE = Path.of("..", "docs", "OBSERVABILITY.md");

  /**
   * Series of the framework's own meters that the dashboard and the rules may use. {@code
   * ObservabilityIT} checks against a running application that they exist.
   */
  static final Set<String> FRAMEWORK_SERIES =
      Set.of(
          "http_server_requests_seconds_count",
          "http_server_requests_seconds_bucket",
          "tomcat_threads_busy_threads",
          "tomcat_threads_config_max_threads",
          "jvm_memory_used_bytes",
          "jvm_memory_max_bytes",
          "jvm_threads_live_threads",
          "process_cpu_usage",
          "system_cpu_usage",
          "hikaricp_connections_active",
          "hikaricp_connections_idle",
          "hikaricp_connections_pending",
          "hikaricp_connections_max",
          "hikaricp_connections_acquire_seconds_sum",
          "hikaricp_connections_acquire_seconds_count",
          "logback_events_total");

  /**
   * Framework series that only appear once something has happened (a garbage collection), and
   * Prometheus's own {@code up}; allowed, but not checked against a freshly started application.
   */
  private static final Set<String> NOT_ALWAYS_PRESENT = Set.of("jvm_gc_pause_seconds_sum", "up");

  /**
   * Every tag a qnop meter may carry. Each is a closed set (ADR-0065); a new key belongs here only
   * if its values are, too, and never if it could hold a user, a document, a key or a host.
   */
  private static final Set<String> TAG_KEYS =
      Set.of(
          "state",
          "status",
          "outcome",
          "type",
          "job",
          "trigger",
          "dry_run",
          "format",
          "operation",
          "direction",
          "template",
          "method",
          "scope",
          "result",
          "provider",
          "version",
          "edition");

  /** A metric name as it appears in a PromQL expression: an identifier before a selector. */
  private static final Pattern SERIES = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\{");

  /** A qnop meter name as the catalogue lists it: the first cell of a table row. */
  private static final Pattern DOCUMENTED =
      Pattern.compile("^\\| `(qnop\\.[a-z.]+)`", Pattern.MULTILINE);

  /** What Prometheus appends to a meter's name: a unit, then the series of its type. */
  private static final Pattern SERIES_SUFFIX =
      Pattern.compile("(_seconds|_bytes)?(_total|_count|_sum|_bucket)?");

  private static PrometheusMeterRegistry registry;
  private static Set<String> published;

  @BeforeAll
  static void publishEverything() {
    registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    PublishedMeters.registerAllOn(registry);
    published =
        registry
            .scrape()
            .lines()
            .filter(line -> !line.startsWith("#") && !line.isBlank())
            .map(line -> line.split("[{ ]", 2)[0])
            .collect(Collectors.toCollection(TreeSet::new));
  }

  @Test
  @DisplayName("every series a dashboard panel queries is published")
  void dashboardQueriesOnlyPublishedSeries() throws IOException {
    assertThat(referencedSeries(DASHBOARD)).isNotEmpty().allSatisfy(this::assertKnown);
  }

  @Test
  @DisplayName("every series an alert rule evaluates is published")
  void alertRulesEvaluateOnlyPublishedSeries() throws IOException {
    assertThat(referencedSeries(ALERTS)).isNotEmpty().allSatisfy(this::assertKnown);
  }

  @Test
  @DisplayName("every published meter is on the dashboard")
  void everyMeterIsOnTheDashboard() throws IOException {
    Set<String> queried = referencedSeries(DASHBOARD);

    // By family: a histogram is on the dashboard if any of its series is queried.
    assertThat(qnopMeterNames())
        .allSatisfy(
            name ->
                assertThat(queried)
                    .as("a panel that queries %s", name)
                    .anyMatch(series -> isSeriesOf(series, name.replace('.', '_'))));
  }

  /** Whether {@code series} is {@code family} itself or one of its unit and histogram series. */
  private static boolean isSeriesOf(String series, String family) {
    return series.startsWith(family)
        && SERIES_SUFFIX.matcher(series.substring(family.length())).matches();
  }

  @Test
  @DisplayName("the catalogue documents exactly the meters that are published")
  void catalogueMatchesThePublishedMeters() throws IOException {
    Set<String> documented = new TreeSet<>();
    Matcher matcher = DOCUMENTED.matcher(Files.readString(CATALOGUE));
    while (matcher.find()) {
      documented.add(matcher.group(1));
    }

    assertThat(documented).containsExactlyInAnyOrderElementsOf(qnopMeterNames());
  }

  @Test
  @DisplayName("a qnop meter carries only tags from the list of closed sets")
  void metersCarryOnlyKnownTags() {
    assertThat(registry.getMeters())
        .filteredOn(meter -> meter.getId().getName().startsWith("qnop."))
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .as("tags of %s", meter.getId().getName())
                    .extracting(Tag::getKey)
                    // The histogram's own bucket label, on the gauges Micrometer derives.
                    .filteredOn(key -> !key.equals("le"))
                    .isSubsetOf(TAG_KEYS));
  }

  @Test
  @DisplayName("every qnop meter is named qnop.<area>.<thing> in lower case")
  void metersFollowTheNamingRule() {
    assertThat(qnopMeterNames()).allMatch(name -> name.matches("qnop(\\.[a-z]+)+"));
  }

  private void assertKnown(String series) {
    if (series.startsWith("qnop_")) {
      assertThat(published).as("published qnop series").contains(series);
    } else {
      assertThat(FRAMEWORK_SERIES.contains(series) || NOT_ALWAYS_PRESENT.contains(series))
          .as("%s is a framework series the dashboard and rules are known to use", series)
          .isTrue();
    }
  }

  private static Set<String> referencedSeries(Path file) throws IOException {
    Set<String> series = new TreeSet<>();
    Matcher matcher = SERIES.matcher(Files.readString(file));
    while (matcher.find()) {
      series.add(matcher.group(1));
    }
    return series;
  }

  private static Set<String> qnopMeterNames() {
    return registry.getMeters().stream()
        .map(Meter::getId)
        .map(Meter.Id::getName)
        .filter(name -> name.startsWith("qnop."))
        // Micrometer's derived ".histogram" gauges are part of their timer, not meters of their
        // own.
        .filter(name -> !name.endsWith(".histogram"))
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
