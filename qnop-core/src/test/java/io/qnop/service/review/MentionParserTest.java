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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the GitHub-style {@code @slug} token parsing (issue #462). */
class MentionParserTest {

  @Test
  void extractsSlugsInFirstSeenOrderLowercasedAndDeduplicated() {
    String body = "Hi @anna-krause and @Ben-Roth — again @ANNA-KRAUSE";

    assertThat(MentionParser.extractSlugs(body)).containsExactly("anna-krause", "ben-roth");
  }

  @Test
  void requiresAWordBoundaryBeforeTheAt() {
    // An email address and an @ glued to a word are not mentions; one after a bracket is.
    String body = "mail a@b-example.org or foo@bar-baz but (@anna-krause) counts";

    assertThat(MentionParser.extractSlugs(body)).containsExactly("anna-krause");
  }

  @Test
  void respectsTheSlugShape() {
    // Too short, hyphen-terminated, underscored and hyphen-led tokens are not slugs.
    String body = "@ab @cde- @under_score @-lead @abc @okay-slug";

    assertThat(MentionParser.extractSlugs(body)).containsExactly("abc", "okay-slug");
  }

  @Test
  void handlesNullBlankAndTokenlessBodies() {
    assertThat(MentionParser.extractSlugs(null)).isEmpty();
    assertThat(MentionParser.extractSlugs("   ")).isEmpty();
    assertThat(MentionParser.extractSlugs("no mentions here")).isEmpty();
  }
}
