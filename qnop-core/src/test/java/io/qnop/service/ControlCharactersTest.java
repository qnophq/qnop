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
package io.qnop.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Control-character detection and neutralisation for single-line text (issue #909). */
class ControlCharactersTest {

  @Test
  void detectsC0C1AndLineSeparators() {
    for (String value : new String[] {"a\rb", "a\nb", "a\tb", "a\u0000b", "a\u0085b", "a b"}) {
      assertThat(ControlCharacters.contains(value)).as(value).isTrue();
    }
  }

  @Test
  void ordinaryTextIsClean() {
    assertThat(ControlCharacters.contains("Rahmenvertrag „Q3“ – Entwurf ✓")).isFalse();
    assertThat(ControlCharacters.contains(null)).isFalse();
  }

  @Test
  void toSpacesReplacesEachControlCharacterWithASpace() {
    assertThat(ControlCharacters.toSpaces("a\r\nb c")).isEqualTo("a  b c");
    assertThat(ControlCharacters.toSpaces("clean")).isEqualTo("clean");
    assertThat(ControlCharacters.toSpaces(null)).isNull();
  }
}
