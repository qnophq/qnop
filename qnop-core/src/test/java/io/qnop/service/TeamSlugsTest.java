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

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TeamSlugs} (issue #470): pure name→slug derivation and collision
 * candidates.
 */
class TeamSlugsTest {

  private static final Pattern SHAPE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  @Test
  @DisplayName("derives a kebab-case slug from a team name")
  void derive() {
    assertThat(TeamSlugs.derive("Contract Review")).isEqualTo("contract-review");
    assertThat(TeamSlugs.derive("  Compliance   Desk  ")).isEqualTo("compliance-desk");
    // Diacritics fold; punctuation collapses to single hyphens.
    assertThat(TeamSlugs.derive("Qualité & Sûreté")).isEqualTo("qualite-surete");
  }

  @Test
  @DisplayName("keeps the output within the ck_team_slug shape for awkward names")
  void awkwardNames() {
    // Slugifies to nothing → fallback.
    assertThat(TeamSlugs.derive("★★★")).isEqualTo("team");
    assertThat(TeamSlugs.derive(null)).isEqualTo("team");
    assertThat(TeamSlugs.derive("")).isEqualTo("team");
    // Too short → suffixed so it clears the 3-char minimum.
    assertThat(TeamSlugs.derive("Q")).isEqualTo("q-team");
    // A UUID-shaped name is defused so routes never mistake a slug for an id.
    String uuidish = "12345678-1234-1234-1234-123456789012";
    assertThat(TeamSlugs.derive(uuidish)).isEqualTo(uuidish + "-team");
    assertThat(SHAPE.matcher(TeamSlugs.derive("Q")).matches()).isTrue();
  }

  @Test
  @DisplayName("candidate appends -n on collision and never exceeds 64 chars")
  void candidate() {
    assertThat(TeamSlugs.candidate("core", 1)).isEqualTo("core");
    assertThat(TeamSlugs.candidate("core", 2)).isEqualTo("core-2");
    assertThat(TeamSlugs.candidate("core", 17)).isEqualTo("core-17");

    String long63 = "a".repeat(63);
    String candidate = TeamSlugs.candidate(long63, 42);
    assertThat(candidate.length()).isLessThanOrEqualTo(64);
    assertThat(candidate).endsWith("-42");
    assertThat(SHAPE.matcher(candidate).matches()).isTrue();
  }
}
