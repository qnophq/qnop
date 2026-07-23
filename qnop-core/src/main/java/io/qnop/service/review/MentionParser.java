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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts mentioned user ids from a comment body (issue #462). The composer inserts a canonical,
 * id-based token — {@code @[Display Name](mention:<uuid>)} — rather than {@code @username}, because
 * OIDC users may have no username while every roster member has an id. The token is shaped like a
 * Markdown link, so an un-enhanced renderer still shows the name; qnop's renderer special-cases the
 * {@code mention:} scheme into a highlighted profile link.
 *
 * <p>Pure and DB-free: this only pulls the ids out of the text. Access-scoping (keep only ids on
 * the document roster) and anonymity handling live in {@link CommentMentionService}.
 */
public final class MentionParser {

  /** {@code @[label](mention:<uuid>)} — the label is free text, the id is a canonical UUID. */
  private static final Pattern MENTION =
      Pattern.compile(
          "@\\[[^\\]]*]\\(mention:"
              + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");

  private MentionParser() {}

  /**
   * The distinct user ids mentioned in {@code body}, in first-seen order. A malformed or empty body
   * yields an empty set; duplicate mentions of the same id collapse to one.
   */
  public static Set<UUID> extractUserIds(String body) {
    if (body == null || body.isBlank()) {
      return Set.of();
    }
    Set<UUID> ids = new LinkedHashSet<>();
    Matcher matcher = MENTION.matcher(body);
    while (matcher.find()) {
      // The capture group is already a well-formed UUID by the pattern; guard defensively anyway.
      try {
        ids.add(UUID.fromString(matcher.group(1)));
      } catch (IllegalArgumentException ignored) {
        // not a parseable UUID — treat the token as plain text
      }
    }
    return ids;
  }
}
