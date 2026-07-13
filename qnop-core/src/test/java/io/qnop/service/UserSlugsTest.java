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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Profile-slug derivation (issue #486): kebab-case, 3-64 chars, never UUID-shaped. */
class UserSlugsTest {

  @Test
  @DisplayName("derives a kebab-case slug from a display name")
  void derivesKebabCase() {
    assertThat(UserSlugs.derive("Anna Krause")).isEqualTo("anna-krause");
    assertThat(UserSlugs.derive("  J.  R.  R.  Tolkien ")).isEqualTo("j-r-r-tolkien");
    assertThat(UserSlugs.derive("O'Brien, Seán")).isEqualTo("o-brien-sean");
  }

  @Test
  @DisplayName("folds diacritics instead of dropping the letters")
  void foldsDiacritics() {
    assertThat(UserSlugs.derive("Åsa Ödegård-Müller")).isEqualTo("asa-odegard-muller");
    assertThat(UserSlugs.derive("François Nuñez")).isEqualTo("francois-nunez");
  }

  @Test
  @DisplayName("falls back for names that slugify to nothing or too little")
  void fallsBack() {
    assertThat(UserSlugs.derive(null)).isEqualTo("user");
    assertThat(UserSlugs.derive("   ")).isEqualTo("user");
    assertThat(UserSlugs.derive("霞")).isEqualTo("user");
    assertThat(UserSlugs.derive("Al")).isEqualTo("al-user");
  }

  @Test
  @DisplayName("never yields a UUID-shaped slug (routes resolve those as ids)")
  void neverUuidShaped() {
    assertThat(UserSlugs.derive("123e4567-e89b-12d3-a456-426614174000"))
        .isEqualTo("123e4567-e89b-12d3-a456-426614174000-user");
  }

  @Test
  @DisplayName("truncates to 64 characters without a trailing hyphen")
  void truncates() {
    String slug = UserSlugs.derive("x".repeat(63) + " tail");
    assertThat(slug).hasSize(63).isEqualTo("x".repeat(63));
  }

  @Test
  @DisplayName("suffixes collision candidates, keeping the total within 64 characters")
  void collisionCandidates() {
    assertThat(UserSlugs.candidate("anna-krause", 1)).isEqualTo("anna-krause");
    assertThat(UserSlugs.candidate("anna-krause", 2)).isEqualTo("anna-krause-2");
    assertThat(UserSlugs.candidate("anna-krause", 13)).isEqualTo("anna-krause-13");

    String longBase = "x".repeat(64);
    String candidate = UserSlugs.candidate(longBase, 2);
    assertThat(candidate).hasSize(64).endsWith("-2").doesNotContain("--");
  }
}
