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
package io.qnop.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.tracking.TrackingMeasurementValidator.Measurement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** What the unauthenticated collect endpoint accepts, and everything it does not (issue #910). */
class TrackingMeasurementValidatorTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /**
   * Written by the client's {@code providers.test.ts} from the real request builders — the one
   * source both sides are tested against, so neither can drift without a failing test.
   */
  private static final Path GOLDEN =
      Path.of("..", "qnop-ui", "src", "tracking", "__fixtures__", "beacons.json");

  private static final String SITE = "site-1";

  record Golden(
      String provider, String kind, String method, String path, String body, String type) {
    String route() {
      int q = path.indexOf('?');
      return q < 0 ? path : path.substring(0, q);
    }

    String query() {
      int q = path.indexOf('?');
      return q < 0 ? null : path.substring(q + 1);
    }

    byte[] bytes() {
      return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    }

    TrackingProvider backend() {
      return TrackingProvider.fromId(provider).orElseThrow();
    }
  }

  static List<Golden> golden() throws IOException {
    JsonNode root = MAPPER.readTree(Files.readString(GOLDEN));
    List<Golden> out = new ArrayList<>();
    for (JsonNode n : root) {
      out.add(
          new Golden(
              n.get("provider").stringValue(),
              n.get("kind").stringValue(),
              n.get("method").stringValue(),
              n.get("path").stringValue(),
              n.has("body") ? n.get("body").stringValue() : null,
              n.has("contentType") ? n.get("contentType").stringValue() : null));
    }
    return out;
  }

  static Stream<Golden> goldenBeacons() throws IOException {
    return golden().stream();
  }

  private static TrackingRuntimeConfig config(TrackingProvider provider) {
    return new TrackingRuntimeConfig(
        provider,
        SITE,
        "https://analytics.example",
        true,
        true,
        false,
        ClientIpForwarding.ANONYMIZED);
  }

  private static Optional<Measurement> validate(
      TrackingProvider provider, String method, String path, String query, String body) {
    return TrackingMeasurementValidator.validate(
        config(provider),
        method,
        path,
        query,
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenBeacons")
  @DisplayName("every request qnop's client builds is accepted unchanged")
  void acceptsTheClientsOwnRequests(Golden beacon) {
    Optional<Measurement> result =
        TrackingMeasurementValidator.validate(
            config(beacon.backend()),
            beacon.method(),
            beacon.route(),
            beacon.query(),
            beacon.bytes());

    assertThat(result).as("%s %s", beacon.provider(), beacon.kind()).isPresent();
    Measurement m = result.get();
    assertThat(m.method()).isEqualTo(beacon.method());
    if (beacon.body() == null) {
      assertThat(m.query()).isEqualTo(beacon.query());
    } else {
      assertThat(MAPPER.readTree(m.body())).isEqualTo(MAPPER.readTree(beacon.body()));
      assertThat(m.contentType()).isEqualTo(beacon.type());
    }
  }

  @Test
  @DisplayName("the client sends to exactly the paths each provider allows")
  void clientPathsEqualTheAllowlist() throws IOException {
    Map<TrackingProvider, Set<String>> sent = new HashMap<>();
    for (Golden beacon : golden()) {
      sent.computeIfAbsent(beacon.backend(), p -> new LinkedHashSet<>()).add(beacon.route());
    }
    for (TrackingProvider provider : TrackingProvider.values()) {
      assertThat(sent.get(provider))
          .as(provider.id().toLowerCase(Locale.ROOT))
          .containsExactlyInAnyOrderElementsOf(provider.collectPaths());
    }
  }

  @Test
  @DisplayName("fields qnop never sends are refused, not stripped")
  void refusesUnknownFields() {
    assertThat(
            validate(
                TrackingProvider.UMAMI,
                "POST",
                "/api/send",
                null,
                "{\"type\":\"event\",\"payload\":{\"website\":\"site-1\",\"hostname\":\"q\","
                    + "\"screen\":\"1x1\",\"url\":\"/\",\"data\":{\"email\":\"a@b.c\"}}}"))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.UMAMI,
                "POST",
                "/api/send",
                null,
                "{\"type\":\"identify\",\"payload\":{\"website\":\"site-1\",\"hostname\":\"q\","
                    + "\"screen\":\"1x1\",\"url\":\"/\"}}"))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.MATOMO,
                "GET",
                "/matomo.php",
                "idsite=site-1&rec=1&url=%2F&uid=someone",
                null))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.PLAUSIBLE,
                "POST",
                "/api/event",
                null,
                "{\"name\":\"pageview\",\"url\":\"/\",\"domain\":\"site-1\",\"props\":{\"a\":1}}"))
        .isEmpty();
  }

  @Test
  @DisplayName("PostHog events stay anonymous whatever the caller asks for")
  void posthogStaysAnonymous() {
    String base = "{\"api_key\":\"site-1\",\"event\":\"$pageview\",\"distinct_id\":\"v-1\",";
    // Person properties and identification are not part of what qnop sends.
    assertThat(
            validate(
                TrackingProvider.POSTHOG,
                "POST",
                "/i/v0/e/",
                null,
                base + "\"properties\":{\"$current_url\":\"/\",\"$set\":{\"email\":\"x\"}}}"))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.POSTHOG,
                "POST",
                "/i/v0/e/",
                null,
                base + "\"properties\":{\"$current_url\":\"/\",\"$process_person_profile\":true}}"))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.POSTHOG,
                "POST",
                "/i/v0/e/",
                null,
                base.replace("$pageview", "$identify")
                    + "\"properties\":{\"$current_url\":\"/\"}}"))
        .isEmpty();
    // Left out, it is forced on the way through.
    Optional<Measurement> forced =
        validate(
            TrackingProvider.POSTHOG,
            "POST",
            "/i/v0/e/",
            null,
            base + "\"properties\":{\"$current_url\":\"/\"}}");
    assertThat(forced).isPresent();
    JsonNode props = MAPPER.readTree(forced.get().body()).get("properties");
    assertThat(props.get("$process_person_profile").booleanValue()).isFalse();
  }

  @Test
  @DisplayName("measurements for another site on the same backend are refused")
  void refusesAnotherSiteId() {
    assertThat(
            validate(TrackingProvider.MATOMO, "GET", "/matomo.php", "idsite=2&rec=1&url=%2F", null))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.PLAUSIBLE,
                "POST",
                "/api/event",
                null,
                "{\"name\":\"pageview\",\"url\":\"/\",\"domain\":\"other.example\"}"))
        .isEmpty();
    assertThat(validate(TrackingProvider.PIRSCH, "GET", "/hit", "code=other&url=%2F", null))
        .isEmpty();
  }

  @Test
  @DisplayName("only the four named events exist")
  void refusesEventsOutsideTheClosedList() {
    assertThat(
            validate(
                TrackingProvider.MATOMO,
                "GET",
                "/matomo.php",
                "idsite=site-1&rec=1&url=%2F&e_c=qnop&e_a=document_title_here",
                null))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.PIRSCH,
                "POST",
                "/event",
                null,
                "{\"identification_code\":\"site-1\",\"url\":\"/\",\"event_name\":\"free text\"}"))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.PIRSCH,
                "POST",
                "/event",
                null,
                "{\"identification_code\":\"site-1\",\"url\":\"/\",\"event_name\":\"review_created\","
                    + "\"event_meta\":{\"title\":\"Contract\"}}"))
        .isEmpty();
  }

  @Test
  @DisplayName("the wrong method, a stray query, a duplicate or a non-JSON body is refused")
  void refusesMalformedRequests() {
    assertThat(
            validate(
                TrackingProvider.MATOMO,
                "POST",
                "/matomo.php",
                "idsite=site-1&rec=1&url=%2F",
                null))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.MATOMO,
                "GET",
                "/matomo.php",
                "idsite=site-1&idsite=site-1&rec=1&url=%2F",
                null))
        .isEmpty();
    assertThat(
            validate(
                TrackingProvider.PLAUSIBLE,
                "POST",
                "/api/event",
                "x=1",
                "{\"name\":\"pageview\",\"url\":\"/\",\"domain\":\"site-1\"}"))
        .isEmpty();
    assertThat(validate(TrackingProvider.UMAMI, "POST", "/api/send", null, "not json")).isEmpty();
    assertThat(validate(TrackingProvider.UMAMI, "POST", "/api/batch", null, "[]")).isEmpty();
  }

  @Test
  @DisplayName("the content type is pinned, never the caller's")
  void pinsTheContentType() {
    Optional<Measurement> umami =
        validate(
            TrackingProvider.UMAMI,
            "POST",
            "/api/send",
            null,
            "{\"type\":\"event\",\"payload\":{\"website\":\"site-1\",\"hostname\":\"q.example\","
                + "\"screen\":\"1x1\",\"url\":\"/\"}}");
    assertThat(umami)
        .hasValueSatisfying(m -> assertThat(m.contentType()).isEqualTo("application/json"));
  }
}
