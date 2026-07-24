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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts mentioned profile slugs from a comment body (issue #462). The mention token is plain
 * GitHub-style text — {@code @<slug>} — using the immutable, unique profile slug every account
 * carries (issue #486), so the raw text stays human-readable and the reference survives
 * display-name changes. A token only counts at a word boundary ({@code a@b.com} is not a mention),
 * and matching ignores case like every slug lookup.
 *
 * <p>Pure and DB-free: this only pulls the slugs out of the text. Resolution to users,
 * access-scoping and the anonymity policy live in {@link CommentMentionService}.
 */
public final class MentionParser {

  /**
   * {@code @slug} after start/whitespace/bracket — slug shape per issue #486: letters, digits and
   * inner hyphens, 3–64 chars, never hyphen-terminated (a trailing hyphen stays outside the token).
   */
  private static final Pattern MENTION =
      Pattern.compile("(?:^|[\\s(\\[{>])@([A-Za-z0-9][A-Za-z0-9-]{1,62}[A-Za-z0-9])(?![\\w-])");

  private MentionParser() {}

  /**
   * The distinct slugs mentioned in {@code body}, lower-cased, in first-seen order. A null, blank
   * or tokenless body yields an empty set; duplicate mentions of the same slug collapse to one.
   */
  public static Set<String> extractSlugs(String body) {
    if (body == null || body.isBlank()) {
      return Set.of();
    }
    Set<String> slugs = new LinkedHashSet<>();
    Matcher matcher = MENTION.matcher(body);
    while (matcher.find()) {
      slugs.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
    return slugs;
  }
}
