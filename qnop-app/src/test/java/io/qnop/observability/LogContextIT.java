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
package io.qnop.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The diagnostic context, proven against the console rather than the configuration (issue #659).
 *
 * <p>The failure mode this exists for is silent: MDC values can be set perfectly and never reach a
 * log line, because Boot's default pattern prints none of them. Asserting the property is set would
 * prove nothing, so these tests read what was actually written.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogContextIT extends SeededIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(LogContextIT.class);

  @Test
  @DisplayName("a request gets an id, returns it, and stamps it on the lines it produces")
  void requestIdReachesTheOutput(CapturedOutput output) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/config").header("Authorization", "Bearer " + token(MEMBER_ID)))
            .andExpect(status().isOk())
            .andReturn();

    String requestId = result.getResponse().getHeader("X-Request-Id");
    assertThat(requestId).isNotBlank();

    // The header alone would only prove the filter ran. Writing a line while the
    // context is what the request left behind proves the pattern renders it.
    MDC.put(LogContext.REQUEST_ID, requestId);
    MDC.put(LogContext.USER_ID, MEMBER_ID.toString());
    try {
      log.info("a line written inside a request context");
    } finally {
      MDC.clear();
    }

    // The composed field, not the two ids somewhere on the page: a loose contains
    // would pass on a line that merely happens to mention them, which is exactly
    // the false confidence this test exists to avoid.
    assertThat(output).contains("[" + requestId + " " + MEMBER_ID + " ]");
  }

  @Test
  @DisplayName("an inbound request id is honoured, so a call can be traced across services")
  void inboundRequestIdIsKept() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/config").header("X-Request-Id", "edge-7f31a2"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("edge-7f31a2");
  }

  @Test
  @DisplayName("an inbound id that could forge a log line is replaced, not trusted")
  void hostileRequestIdIsReplaced() throws Exception {
    // A newline in an id lets a caller write their own log lines. The value ends
    // up in a file people read as a record, so it is not taken on trust.
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/config").header("X-Request-Id", "abc\nINFO forged line"))
            .andExpect(status().isOk())
            .andReturn();

    String issued = result.getResponse().getHeader("X-Request-Id");
    assertThat(issued).isNotNull().doesNotContain("forged").doesNotContain("\n");
  }

  @Test
  @DisplayName("the context does not survive the request that set it")
  void contextIsClearedBetweenRequests() throws Exception {
    mockMvc
        .perform(get("/api/v1/config").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk());

    // Tomcat reuses threads; MockMvc reuses this one. A value left behind would
    // attribute the next request — a different person — to whoever came before.
    assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
    assertThat(MDC.get(LogContext.USER_ID)).isNull();
  }

  @Test
  @DisplayName("a scope restores what it found, so nesting cannot orphan the outer value")
  void scopesNest() {
    UUID outer = UUID.randomUUID();
    UUID inner = UUID.randomUUID();

    try (LogContext.Scope ignored = LogContext.document(outer)) {
      assertThat(LogContext.get(LogContext.DOCUMENT_ID)).isEqualTo(outer.toString());
      try (LogContext.Scope nested = LogContext.document(inner)) {
        assertThat(LogContext.get(LogContext.DOCUMENT_ID)).isEqualTo(inner.toString());
      }
      // Restored rather than cleared: clearing would leave the rest of the outer
      // scope's lines unattributed.
      assertThat(LogContext.get(LogContext.DOCUMENT_ID)).isEqualTo(outer.toString());
    }
    assertThat(LogContext.get(LogContext.DOCUMENT_ID)).isNull();
  }

  @Test
  @DisplayName("a null value leaves the key absent rather than printing the word null")
  void nullValuesAreAbsent() {
    try (LogContext.Scope ignored = LogContext.scope(LogContext.USER_ID, null)) {
      assertThat(LogContext.get(LogContext.USER_ID)).isNull();
    }
  }
}
