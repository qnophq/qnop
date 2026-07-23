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

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the canonical {@code @[label](mention:uuid)} token parsing (issue #462). */
class MentionParserTest {

  private static final UUID ALICE = UUID.fromString("018f5a3e-0000-7000-8000-000000000001");
  private static final UUID BOB = UUID.fromString("018f5a3e-0000-7000-8000-000000000002");

  private static String token(String label, UUID id) {
    return "@[" + label + "](mention:" + id + ")";
  }

  @Test
  void extractsIdsInFirstSeenOrderAndDeduplicates() {
    String body =
        "Hi "
            + token("Alice", ALICE)
            + " and "
            + token("Bob", BOB)
            + " — again "
            + token("A", ALICE);

    assertThat(MentionParser.extractUserIds(body)).containsExactly(ALICE, BOB);
  }

  @Test
  void ignoresPlainAtTextAndBareUuids() {
    // A plain "@alice", an email, and a naked mention: scheme without the @[..](..) shape are text.
    String body = "email me @alice or a@b.com — mention:" + ALICE + " is not a token";

    assertThat(MentionParser.extractUserIds(body)).isEmpty();
  }

  @Test
  void handlesNullBlankAndTokenlessBodies() {
    assertThat(MentionParser.extractUserIds(null)).isEmpty();
    assertThat(MentionParser.extractUserIds("   ")).isEmpty();
    assertThat(MentionParser.extractUserIds("no mentions here")).isEmpty();
  }
}
