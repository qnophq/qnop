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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.service.PublicProfileService;
import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * The verbose DEBUG trace, and the line it must not cross (issue #659, ADR-0054).
 *
 * <p>Tracing is only worth having if it can be left switchable in a real deployment, and it can
 * only be left switchable if raising the level cannot start writing personal data. The subject here
 * is deliberately {@code getProfileBySlug}: a slug is derived from someone's display name, so an
 * aspect that printed its arguments would put a person's name in the log the moment DEBUG went on.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.io.qnop.trace=DEBUG")
class MethodTraceAspectIT extends SeededIntegrationTest {

  /** Distinctive enough that finding it in the output can only mean the aspect wrote it. */
  private static final String SLUG = "trace-probe-slug-not-a-real-person";

  @Autowired private PublicProfileService profiles;

  @Test
  @DisplayName("a call is traced by name and shape, never by argument value")
  void argumentsAreDescribedNotPrinted(CapturedOutput output) {
    assertThatThrownBy(() -> profiles.getProfileBySlug(SLUG)).isInstanceOf(RuntimeException.class);

    assertThat(output).contains("getProfileBySlug(String(len=" + SLUG.length() + ")");
    // The whole point. If this ever fails, the aspect is writing what users typed.
    assertThat(output).doesNotContain(SLUG);
  }

  @Test
  @DisplayName("a failure is traced as a shape too, so the frame it left is visible")
  void failuresAreTraced(CapturedOutput output) {
    assertThatThrownBy(() -> profiles.getProfileBySlug(SLUG)).isInstanceOf(RuntimeException.class);

    assertThat(output).containsPattern("✗ getProfileBySlug threw \\w+ \\[\\d+ ms]");
  }

  @Test
  @DisplayName("an id is printed, and the call is timed on the way out")
  void idsAreKeptAndCallsAreTimed(CapturedOutput output) {
    profiles.getProfile(MEMBER_ID);

    // A UUID resolves only for somebody with database access; a name resolves for
    // anyone holding the file. That difference is the whole rule.
    assertThat(output).contains("getProfile(" + MEMBER_ID + ")");
    // The duration is why this is worth switching on for a slow flow rather than a
    // broken one, and the return is described by type — never by content.
    assertThat(output).containsPattern("← getProfile = PublicProfileView \\[\\d+ ms]");
  }
}
