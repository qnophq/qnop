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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An unexpected failure must be reconstructible (issue #659).
 *
 * <p>This is the case the whole diagnostic context exists for, and it was the one that used to slip
 * through: with no handler, the exception left the dispatcher, the filter chain unwound, {@link
 * io.qnop.web.RequestLogContextFilter} cleared the MDC, and only then did the servlet container
 * write the stack trace — correlated to nothing.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(UnhandledExceptionLoggingIT.FailingEndpoint.class)
class UnhandledExceptionLoggingIT extends SeededIntegrationTest {

  /** {@code ApiPathConfig} prefixes every {@code @RestController} with {@code /api/v1}. */
  private static final String MAPPING = "/test-only/failure";

  private static final String PATH = "/api/v1" + MAPPING;

  @TestConfiguration
  @RestController
  static class FailingEndpoint {
    @GetMapping(MAPPING)
    String boom() {
      throw new IllegalStateException("the storage backend is unreachable");
    }
  }

  @Test
  @DisplayName("an unhandled failure is logged with its request id and answered with the envelope")
  void unhandledFailureIsLoggedAndCorrelated(CapturedOutput output) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get(PATH).header("Authorization", "Bearer " + token(MEMBER_ID)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn();

    String requestId = result.getResponse().getHeader("X-Request-Id");
    assertThat(requestId).isNotBlank();

    // The two halves that make a report answerable: the failure itself, and the id
    // the user can quote. Either alone is what this issue set out to fix.
    assertThat(output)
        .contains("Unhandled IllegalStateException")
        .contains("the storage backend is unreachable")
        .contains(requestId);
  }

  @Test
  @DisplayName("the internal message is not handed to the caller")
  void internalsStayInternal() throws Exception {
    mockMvc
        .perform(get(PATH).header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("The request could not be completed."));
  }

  @Test
  @DisplayName("a missing route is still a 404, not swallowed into a 500")
  void springsOwnExceptionsAreLeftAlone() throws Exception {
    // The catch-all matches Exception, so the guard against it shadowing Spring's own
    // status-carrying exceptions is the part worth asserting.
    mockMvc
        .perform(get("/api/v1/no-such-route").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isNotFound());
  }
}
