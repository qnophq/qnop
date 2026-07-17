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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression guard for the timestamp L10n policy (issue #465, ADR-0039, requirement 2): the running
 * server's Jackson mapper must serialize every {@link Instant} as an ISO-8601 string carrying an
 * explicit offset ({@code ...Z}), never epoch millis and never a zone-less local string. This is
 * the Jackson 3 default, but it is pinned in {@code application.yml} ({@code
 * spring.jackson.datatype.datetime.WRITE_DATES_AS_TIMESTAMPS: false}) so a future dependency bump
 * cannot silently flip it — this test fails loudly if it ever does.
 *
 * <p>Uses the same {@code @WebMvcTest} slice as {@link ConfigControllerTest} so the autowired
 * mapper is the exact bean the web layer serializes DTOs with; no database, so no
 * Testcontainers/Docker.
 */
@WebMvcTest
@ContextConfiguration(classes = {ApiPathConfig.class})
class TimestampSerializationTest {

  /** A stand-in for any DTO exposing a domain timestamp (e.g. {@code createdAt}). */
  private record TimestampHolder(Instant createdAt) {}

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("Instant serializes as ISO-8601 with an explicit offset, not epoch millis")
  void instantSerializesAsIso8601WithOffset() {
    String json =
        objectMapper.writeValueAsString(new TimestampHolder(Instant.parse("2026-07-17T10:15:30Z")));

    assertThat(json).isEqualTo("{\"createdAt\":\"2026-07-17T10:15:30Z\"}");
  }
}
