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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The last thing that touches a measured URL before it leaves the building (issue #666).
 *
 * <p>Every case here is a way a document id could otherwise end up in someone's analytics report.
 */
class TrackedUrlSanitizerTest {

  @Test
  @DisplayName("leaves a route pattern exactly as the client sent it")
  void keepsPatterns() {
    assertThat(TrackedUrlSanitizer.sanitize("/reviews/:documentId/tasks"))
        .isEqualTo("/reviews/:documentId/tasks");
    assertThat(TrackedUrlSanitizer.sanitize("https://qnop.example/reviews/:documentId"))
        .isEqualTo("https://qnop.example/reviews/:documentId");
  }

  @ParameterizedTest
  @CsvSource({
    // A client that forgot the rule, or an old bundle still in a tab.
    "/reviews/8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f, /reviews/:id",
    "/reviews/8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f/tasks, /reviews/:id/tasks",
    // Numeric and hex ids from anywhere else.
    "/documents/1234567, /documents/:id",
    "/objects/deadbeefcafe1234, /objects/:id",
    // Anything improbably long is treated as an id whatever it is.
    "/x/abcdefghijklmnopqrstuvwxyz0123456789, /x/:id"
  })
  @DisplayName("replaces identifier-shaped segments")
  void replacesIdentifiers(String input, String expected) {
    assertThat(TrackedUrlSanitizer.sanitize(input)).isEqualTo(expected);
  }

  @Test
  @DisplayName("drops the query string, always")
  void dropsQueryAndFragment() {
    // Search terms are the clearest case: "?q=merger agreement" is the document's
    // subject typed by a human, and it has no business travelling.
    assertThat(TrackedUrlSanitizer.sanitize("/search?q=merger%20agreement")).isEqualTo("/search");
    assertThat(TrackedUrlSanitizer.sanitize("/reviews/:documentId#annotation-4"))
        .isEqualTo("/reviews/:documentId");
    assertThat(TrackedUrlSanitizer.sanitize("https://qnop.example/search?q=secret&page=2"))
        .isEqualTo("https://qnop.example/search");
  }

  @Test
  @DisplayName("keeps ordinary page segments readable")
  void keepsReadableSegments() {
    // The whole point is a report that still says something: /admin/settings has
    // to survive, or there is nothing to measure.
    assertThat(TrackedUrlSanitizer.sanitize("/admin/settings")).isEqualTo("/admin/settings");
    assertThat(TrackedUrlSanitizer.sanitize("/my-teams")).isEqualTo("/my-teams");
    assertThat(TrackedUrlSanitizer.sanitize("/")).isEqualTo("/");
  }

  @Test
  @DisplayName("never passes something it could not parse")
  void failsClosed() {
    assertThat(TrackedUrlSanitizer.sanitize(null)).isEqualTo("/");
    assertThat(TrackedUrlSanitizer.sanitize("   ")).isEqualTo("/");
    assertThat(TrackedUrlSanitizer.sanitize("not a url at all")).isEqualTo("/not a url at all");
  }

  @Test
  @DisplayName("a slug is beyond it, and that is the client's job")
  void slugsAreTheClientsJob() {
    // Stated as a test so the limit is visible rather than assumed: this sees a
    // readable word, not a person. The client sends ":userId" here, because the
    // router knows it was a parameter.
    assertThat(TrackedUrlSanitizer.sanitize("/users/mia-member")).isEqualTo("/users/mia-member");
  }
}
