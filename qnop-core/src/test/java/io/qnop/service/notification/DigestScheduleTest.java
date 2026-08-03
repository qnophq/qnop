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
package io.qnop.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a digest is due (issue #680) — all of it timezone arithmetic, so all of it worth pinning.
 */
class DigestScheduleTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

  @Test
  @DisplayName("nothing goes out before the local send hour")
  void tooEarly() {
    // 06:00 in Berlin: the run happens, this recipient does not.
    Instant sixAmBerlin = Instant.parse("2026-08-03T04:00:00Z");

    assertThat(DigestSchedule.isDue(sixAmBerlin, BERLIN, Optional.empty())).isFalse();
  }

  @Test
  @DisplayName("a recipient who never had one gets the first run after their 08:00")
  void firstEver() {
    Instant nineAmBerlin = Instant.parse("2026-08-03T07:00:00Z");

    assertThat(DigestSchedule.isDue(nineAmBerlin, BERLIN, Optional.empty())).isTrue();
  }

  @Test
  @DisplayName("half-hour timezones are served, which an hourly grid alone would miss")
  void halfHourOffset() {
    // 08:00 in Kolkata is 02:30 UTC — no hourly run lands on it. The rule is "at
    // or after", so the 03:00 UTC run (08:30 local) delivers.
    Instant runBefore = Instant.parse("2026-08-03T02:00:00Z"); // 07:30 local
    Instant runAfter = Instant.parse("2026-08-03T03:00:00Z"); // 08:30 local

    assertThat(DigestSchedule.isDue(runBefore, KOLKATA, Optional.empty())).isFalse();
    assertThat(DigestSchedule.isDue(runAfter, KOLKATA, Optional.empty())).isTrue();
  }

  @Test
  @DisplayName("at most one per local day, however often the job runs")
  void onePerDay() {
    Instant nineAm = Instant.parse("2026-08-03T07:00:00Z");
    Instant sixPm = Instant.parse("2026-08-03T16:00:00Z");
    Instant sentThisMorning = Instant.parse("2026-08-03T06:05:00Z");

    // Sent this morning: every later run today declines…
    assertThat(DigestSchedule.isDue(nineAm, BERLIN, Optional.of(sentThisMorning))).isFalse();
    assertThat(DigestSchedule.isDue(sixPm, BERLIN, Optional.of(sentThisMorning))).isFalse();
    // …and tomorrow morning it is due again.
    assertThat(
            DigestSchedule.isDue(
                Instant.parse("2026-08-04T07:00:00Z"), BERLIN, Optional.of(sentThisMorning)))
        .isTrue();
  }

  @Test
  @DisplayName("the local date decides, not the server's")
  void localDateNotServerDate() {
    // 23:30 UTC on the 3rd is already 05:00 on the 4th in Kolkata — before the
    // send hour there, so it is not due even though the server's date rolled.
    Instant lateUtc = Instant.parse("2026-08-03T23:30:00Z");

    assertThat(
            DigestSchedule.isDue(
                lateUtc, KOLKATA, Optional.of(Instant.parse("2026-08-03T03:00:00Z"))))
        .isFalse();
    assertThat(DigestSchedule.localDate(lateUtc, KOLKATA)).isEqualTo(LocalDate.of(2026, 8, 4));
  }

  @Test
  @DisplayName("a first digest reaches back a bounded window, not forever")
  void firstRunIsBounded() {
    Instant now = Instant.parse("2026-08-03T07:00:00Z");

    // Somebody switching to DAILY may have weeks unread; mailing all of it as a
    // "daily" summary would be a worse first impression than what they left.
    assertThat(DigestSchedule.collectFrom(now, Optional.empty()))
        .isEqualTo(now.minus(DigestSchedule.FIRST_RUN_LOOKBACK));
    // With a watermark it is exactly that, so nothing is covered twice or skipped.
    Instant watermark = Instant.parse("2026-08-02T07:03:11Z");
    assertThat(DigestSchedule.collectFrom(now, Optional.of(watermark))).isEqualTo(watermark);
  }

  @Test
  @DisplayName("a recipient who changed timezone is judged by the zone they have now")
  void travellerIsJudgedByTheirCurrentZone() {
    // Storing a local date would have frozen the old zone's calendar: this
    // digest went out at 09:00 Berlin, which is already the 4th in Kolkata, so
    // somebody who moved there is due again on their 4th and not before.
    Instant sentBerlinMorning = Instant.parse("2026-08-03T07:00:00Z");

    assertThat(
            DigestSchedule.isDue(
                Instant.parse("2026-08-03T20:00:00Z"), KOLKATA, Optional.of(sentBerlinMorning)))
        .as("still the same local day in Kolkata (01:30 on the 4th is before 08:00)")
        .isFalse();
    assertThat(
            DigestSchedule.isDue(
                Instant.parse("2026-08-04T03:00:00Z"), KOLKATA, Optional.of(sentBerlinMorning)))
        .as("08:30 on the 4th in Kolkata — a new local day, so due")
        .isTrue();
  }
}
