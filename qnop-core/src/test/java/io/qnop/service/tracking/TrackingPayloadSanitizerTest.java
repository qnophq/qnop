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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Rewriting the URL out of a measurement, whatever body shape it arrives in (issue #666). */
class TrackingPayloadSanitizerTest {

  private static final String DOC = "8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";

  private static String sanitize(String json) {
    return new String(
        TrackingPayloadSanitizer.sanitizeBody(json.getBytes(StandardCharsets.UTF_8)),
        StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("rewrites the flat url field (Plausible)")
  void rewritesFlatUrl() {
    String out =
        sanitize(
            "{\"domain\":\"qnop.example\",\"name\":\"pageview\",\"url\":\"https://qnop.example/reviews/"
                + DOC
                + "\"}");

    assertThat(out).contains("https://qnop.example/reviews/:id").doesNotContain(DOC);
  }

  @Test
  @DisplayName("reaches a nested one (PostHog, Umami)")
  void rewritesNestedUrl() {
    String posthog =
        sanitize(
            "{\"event\":\"$pageview\",\"properties\":{\"$current_url\":\"https://qnop.example/reviews/"
                + DOC
                + "/tasks\"}}");
    assertThat(posthog).contains("/reviews/:id/tasks").doesNotContain(DOC);

    String umami =
        sanitize(
            "{\"type\":\"event\",\"payload\":{\"website\":\"w1\",\"url\":\"/reviews/"
                + DOC
                + "\"}}");
    assertThat(umami).contains("\"url\":\"/reviews/:id\"").doesNotContain(DOC);
  }

  @Test
  @DisplayName("strips the search term out of a referrer as well")
  void rewritesReferrer() {
    // A referrer is a URL like any other, and the one qnop page whose query is
    // the user's own words is the search.
    String out =
        sanitize("{\"name\":\"pageview\",\"referrer\":\"https://qnop.example/search?q=merger\"}");

    assertThat(out).contains("https://qnop.example/search").doesNotContain("merger");
  }

  @Test
  @DisplayName("rewrites the query-string form too (Matomo)")
  void rewritesQueryParameters() {
    String out =
        TrackingPayloadSanitizer.sanitizeQuery(
            "idsite=1&rec=1&url=https%3A%2F%2Fqnop.example%2Freviews%2F"
                + DOC
                + "&action_name=Review");

    assertThat(out).doesNotContain(DOC).contains("idsite=1").contains("rec=1");
  }

  @Test
  @DisplayName("leaves what it cannot read alone rather than mangling it")
  void passesThroughNonJson() {
    byte[] binary = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00};

    assertThat(TrackingPayloadSanitizer.sanitizeBody(binary)).isEqualTo(binary);
    assertThat(TrackingPayloadSanitizer.sanitizeBody(null)).isNull();
    assertThat(TrackingPayloadSanitizer.sanitizeQuery(null)).isNull();
  }

  @Test
  @DisplayName("keeps everything that is not a URL exactly as it was")
  void keepsOtherFields() {
    String out = sanitize("{\"name\":\"review_created\",\"domain\":\"qnop.example\",\"count\":3}");

    assertThat(out).contains("review_created").contains("qnop.example").contains("3");
  }
}
