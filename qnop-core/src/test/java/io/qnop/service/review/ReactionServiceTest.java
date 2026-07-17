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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.entity.Reaction;
import io.qnop.service.document.DocumentValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The DB-free grouping and validation halves of {@link ReactionService} (issue #410). */
class ReactionServiceTest {

  private static final UUID ANNOTATION = UUID.randomUUID();
  private static final UUID ANNA = UUID.randomUUID();
  private static final UUID BEN = UUID.randomUUID();
  private static final UUID CARA = UUID.randomUUID();

  private static final Map<UUID, String> NAMES = Map.of(ANNA, "Anna", BEN, "Ben", CARA, "Cara");
  private static final Function<UUID, String> NAME_OF = NAMES::get;

  @Test
  @DisplayName("groups by emoji in first-appearance order with counts and reactor names")
  void groupsByEmojiInFirstAppearanceOrder() {
    List<Reaction> reactions =
        List.of(
            Reaction.onAnnotation(ANNOTATION, ANNA, "👍"),
            Reaction.onAnnotation(ANNOTATION, BEN, "🎉"),
            Reaction.onAnnotation(ANNOTATION, BEN, "👍"),
            Reaction.onAnnotation(ANNOTATION, CARA, "👍"));

    List<ReactionService.ReactionGroup> groups = ReactionService.group(reactions, ANNA, NAME_OF);

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).emoji()).isEqualTo("👍");
    assertThat(groups.get(0).count()).isEqualTo(3);
    assertThat(groups.get(0).reactors()).containsExactly("Anna", "Ben", "Cara");
    assertThat(groups.get(1).emoji()).isEqualTo("🎉");
    assertThat(groups.get(1).count()).isEqualTo(1);
  }

  @Test
  @DisplayName("marks only the viewer's own groups as reactedByMe")
  void marksOwnGroups() {
    List<Reaction> reactions =
        List.of(
            Reaction.onAnnotation(ANNOTATION, ANNA, "👍"),
            Reaction.onAnnotation(ANNOTATION, BEN, "🎉"));

    List<ReactionService.ReactionGroup> groups = ReactionService.group(reactions, ANNA, NAME_OF);

    assertThat(groups.get(0).reactedByMe()).isTrue();
    assertThat(groups.get(1).reactedByMe()).isFalse();
  }

  @Test
  @DisplayName("distinct skin tones and ZWJ sequences stay distinct groups")
  void groupsVerbatim() {
    List<Reaction> reactions =
        List.of(
            Reaction.onAnnotation(ANNOTATION, ANNA, "👍"),
            Reaction.onAnnotation(ANNOTATION, BEN, "👍🏽"));

    assertThat(ReactionService.group(reactions, ANNA, NAME_OF)).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"👍", "🎉", "👍🏽", "👨‍👩‍👧‍👦", "1️⃣", "❤️"})
  @DisplayName("accepts plausible emoji, incl. keycaps, skin tones and ZWJ families")
  void acceptsEmoji(String emoji) {
    assertThat(ReactionService.requireEmoji(emoji)).isEqualTo(emoji);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", ":)", "x", "1", "👍 👍"})
  @DisplayName("rejects prose, pure ASCII and whitespace-bearing input")
  void rejectsNonEmoji(String input) {
    assertThatThrownBy(() -> ReactionService.requireEmoji(input))
        .isInstanceOf(DocumentValidationException.class);
  }

  @Test
  @DisplayName("rejects null, empty and over-long input")
  void rejectsDegenerateInput() {
    assertThatThrownBy(() -> ReactionService.requireEmoji(null))
        .isInstanceOf(DocumentValidationException.class);
    assertThatThrownBy(() -> ReactionService.requireEmoji(""))
        .isInstanceOf(DocumentValidationException.class);
    assertThatThrownBy(() -> ReactionService.requireEmoji("👍".repeat(20)))
        .isInstanceOf(DocumentValidationException.class);
  }
}
