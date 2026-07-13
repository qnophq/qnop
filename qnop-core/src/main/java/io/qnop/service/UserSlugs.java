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

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure, DB-free derivation of profile slugs from display names (issue #486). The output always
 * satisfies the {@code ck_qnop_user_slug} shape (kebab-case, 3-64 characters) and is never
 * UUID-shaped, since routes resolve UUID-shaped segments as user ids. Uniqueness is NOT handled
 * here — {@link UserSlugService} probes {@link #candidate(String, int)} against the database.
 */
public final class UserSlugs {

  static final int MAX_LENGTH = 64;
  private static final int MIN_LENGTH = 3;

  /** Base for names that slugify to nothing (e.g. purely non-Latin symbols). */
  private static final String FALLBACK = "user";

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
  private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");
  private static final Pattern TRAILING_HYPHENS = Pattern.compile("-+$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private UserSlugs() {}

  /**
   * Derives the base slug for a display name: diacritics folded (NFKD), lowercased, every
   * non-alphanumeric run collapsed to a single hyphen. Too-short (and, defensively, UUID-shaped)
   * results are suffixed with {@code -user}; empty results fall back to {@code user}.
   */
  public static String derive(String displayName) {
    String folded =
        displayName == null
            ? ""
            : MARKS
                .matcher(Normalizer.normalize(displayName, Normalizer.Form.NFKD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    String base = EDGE_HYPHENS.matcher(NON_ALNUM.matcher(folded).replaceAll("-")).replaceAll("");
    if (base.isEmpty()) {
      return FALLBACK;
    }
    if (base.length() < MIN_LENGTH || UUID_SHAPE.matcher(base).matches()) {
      base = base + "-" + FALLBACK;
    }
    return truncate(base);
  }

  /**
   * The n-th collision candidate for a base slug: the base itself for attempt 1, {@code base-n}
   * afterwards — truncating the base so the suffixed result stays within {@value #MAX_LENGTH}
   * characters without a doubled hyphen.
   */
  public static String candidate(String base, int attempt) {
    if (attempt <= 1) {
      return base;
    }
    String suffix = "-" + attempt;
    String head = base;
    if (head.length() + suffix.length() > MAX_LENGTH) {
      head =
          TRAILING_HYPHENS.matcher(head.substring(0, MAX_LENGTH - suffix.length())).replaceAll("");
    }
    return head + suffix;
  }

  private static String truncate(String slug) {
    if (slug.length() <= MAX_LENGTH) {
      return slug;
    }
    return TRAILING_HYPHENS.matcher(slug.substring(0, MAX_LENGTH)).replaceAll("");
  }
}
