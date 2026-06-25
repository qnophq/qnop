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
package io.qnop.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the generated-password contract (issue #116): length, readable charset, and uniqueness. */
class PasswordGeneratorTest {

  @Test
  @DisplayName("generates a 16-character password drawn only from the readable alphabet")
  void lengthAndCharset() {
    String password = PasswordGenerator.generate();

    assertThat(password).hasSize(16);
    assertThat(password.chars()).allMatch(c -> PasswordGenerator.ALPHABET.indexOf(c) >= 0);
  }

  @Test
  @DisplayName("never emits the ambiguous glyphs 0 O 1 l I")
  void excludesAmbiguousGlyphs() {
    String many =
        IntStream.range(0, 200)
            .mapToObj(i -> PasswordGenerator.generate())
            .reduce("", String::concat);

    assertThat(many).doesNotContainAnyWhitespaces();
    assertThat(many.chars()).noneMatch(c -> "0O1lI".indexOf(c) >= 0);
  }

  @Test
  @DisplayName("clears the 8-character policy minimum with margin")
  void exceedsPolicyMinimum() {
    assertThat(PasswordGenerator.generate().length()).isGreaterThanOrEqualTo(8);
  }

  @Test
  @DisplayName("is effectively unique across many calls (no fixed/degenerate output)")
  void uniqueAcrossCalls() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      seen.add(PasswordGenerator.generate());
    }

    assertThat(seen).hasSize(500);
  }
}
