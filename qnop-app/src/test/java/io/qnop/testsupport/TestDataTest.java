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
package io.qnop.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Confirms the {@code testdata/} wiring end-to-end (no Docker): the Gradle-provided {@code
 * qnop.testdata.dir} property and the {@link TestData} resolver locate and read the shared
 * fixtures.
 */
class TestDataTest {

  @Test
  void resolvesAndReadsABrandingPngFixture() {
    byte[] png = TestData.bytes("branding/logo-light.png");
    assertThat(png).isNotEmpty();
    assertThat(png[0] & 0xFF).isEqualTo(0x89);
    assertThat(new String(png, 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");
  }

  @Test
  void readsATextFixture() {
    assertThat(new String(TestData.bytes("branding/not-an-image.txt"), StandardCharsets.UTF_8))
        .contains("not an image");
  }
}
