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
package io.qnop.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The {@code expiresAtHuman} phrase (issues #140, #853). */
class RelativeTimePhraseTest {

  private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");
  private static final MailMessages EN = MailMessages.forLocale("en");
  private static final MailMessages DE = MailMessages.forLocale("de");

  private static String in(Duration duration, MailMessages messages) {
    return RelativeTimePhrase.until(NOW, NOW.plus(duration), messages);
  }

  @Test
  @DisplayName("English keeps its singulars and plurals")
  void english() {
    assertThat(in(Duration.ofSeconds(10), EN)).isEqualTo("in 1 minute");
    assertThat(in(Duration.ofMinutes(30), EN)).isEqualTo("in 30 minutes");
    assertThat(in(Duration.ofMinutes(60), EN)).isEqualTo("in 1 hour");
    assertThat(in(Duration.ofHours(24), EN)).isEqualTo("in 24 hours");
    assertThat(in(Duration.ofHours(48), EN)).isEqualTo("in 2 days");
    assertThat(in(Duration.ofDays(7), EN)).isEqualTo("in 7 days");
  }

  @Test
  @DisplayName("German declines the units correctly")
  void german() {
    assertThat(in(Duration.ofSeconds(10), DE)).isEqualTo("in 1 Minute");
    assertThat(in(Duration.ofMinutes(30), DE)).isEqualTo("in 30 Minuten");
    assertThat(in(Duration.ofMinutes(60), DE)).isEqualTo("in 1 Stunde");
    assertThat(in(Duration.ofHours(24), DE)).isEqualTo("in 24 Stunden");
    assertThat(in(Duration.ofDays(1).plusHours(1), DE)).isEqualTo("in 25 Stunden");
    assertThat(in(Duration.ofDays(1), DE)).isEqualTo("in 24 Stunden");
    assertThat(in(Duration.ofDays(2), DE)).isEqualTo("in 2 Tagen");
  }

  @Test
  @DisplayName("English is byte-identical to the pre-#853 phrase over the whole range")
  void englishMatchesTheLegacyPhrase() {
    for (long minutes = 0; minutes <= 60 * 24 * 40; minutes += 7) {
      Instant expiresAt = NOW.plus(Duration.ofMinutes(minutes));
      assertThat(RelativeTimePhrase.until(NOW, expiresAt, EN))
          .as("%d minutes", minutes)
          .isEqualTo(legacy(NOW, expiresAt));
    }
  }

  /** The English implementation as it was before issue #853. */
  private static String legacy(Instant now, Instant expiresAt) {
    long minutes = Math.max(1, Math.round(Duration.between(now, expiresAt).toSeconds() / 60.0));
    if (minutes < 60) {
      return "in " + plural(minutes, "minute");
    }
    long hours = Math.round(minutes / 60.0);
    if (hours < 48) {
      return "in " + plural(hours, "hour");
    }
    long days = Math.round(hours / 24.0);
    return "in " + plural(days, "day");
  }

  private static String plural(long count, String unit) {
    return count + " " + unit + (count == 1 ? "" : "s");
  }
}
