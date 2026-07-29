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
package io.qnop.service.review.export;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The name an export is saved under (issue #635 follow-up).
 *
 * <p>The default is {@code <slug>-annotations.<ext>}, derived from the review's title, because that
 * is what a folder full of exports needs to stay legible. The user may replace it — a report going
 * to a customer is rarely best named after an internal document title.
 *
 * <p>Deliberately not {@link io.qnop.service.UserSlugs}: that derivation is about profile slugs and
 * carries rules that make no sense here — a {@code -user} suffix for short names, a guard against
 * UUID-shaped results because routes resolve those as ids. Sharing it would mean a filename
 * inheriting constraints from URL routing.
 *
 * <p>The result reaches a {@code Content-Disposition} header, so sanitizing is not cosmetic: a name
 * carrying a newline or a quote would be header injection, and one carrying a path separator would
 * be a download that tries to escape the downloads folder. Everything outside a conservative set is
 * folded away rather than escaped, since a filename has no need for the rest.
 */
public final class ExportFilename {

  /** Long enough for a descriptive name, short enough for every filesystem to accept it. */
  static final int MAX_BASE_LENGTH = 80;

  /** What a title that slugifies to nothing gets. */
  private static final String FALLBACK = "annotations";

  private static final String SUFFIX = "-annotations";

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
  private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");

  /**
   * Characters a filename may keep. Letters and digits in any script, plus the punctuation people
   * actually use in names — everything else, including quotes, separators and control characters,
   * collapses to a hyphen.
   */
  private static final Pattern UNSAFE = Pattern.compile("[^\\p{L}\\p{N} ._-]+");

  private static final Pattern REPEATED_HYPHENS = Pattern.compile("-{2,}");

  private ExportFilename() {}

  /**
   * The filename for one export.
   *
   * @param requested what the user typed, or null/blank to use the default
   * @param documentTitle the review's title, which the default is derived from
   */
  public static String forExport(
      String requested, String documentTitle, AnnotationExportFormat format) {
    String extension = format.getExtension();
    String base = clean(stripExtension(requested, extension));
    if (base.isEmpty()) {
      base = defaultBase(documentTitle);
    }
    return base + extension;
  }

  /** {@code <slug>-annotations}, without the extension. */
  public static String defaultBase(String documentTitle) {
    String slug = slug(documentTitle);
    return (slug.isEmpty() ? FALLBACK : slug + SUFFIX);
  }

  /** Kebab-case, diacritics folded, non-alphanumeric runs collapsed. */
  static String slug(String title) {
    if (title == null) {
      return "";
    }
    String folded =
        MARKS
            .matcher(Normalizer.normalize(title, Normalizer.Form.NFKD))
            .replaceAll("")
            .toLowerCase(Locale.ROOT);
    String slug = EDGE_HYPHENS.matcher(NON_SLUG.matcher(folded).replaceAll("-")).replaceAll("");
    return truncate(slug);
  }

  /**
   * Drops the extension the user typed, but only when it is the one this export will carry.
   *
   * <p>A name like {@code contract-v1.2} must keep its dot; only {@code report.docx} on a Word
   * export is the user restating what the format already decides.
   */
  private static String stripExtension(String requested, String extension) {
    if (requested == null) {
      return "";
    }
    String trimmed = requested.trim();
    return trimmed.toLowerCase(Locale.ROOT).endsWith(extension)
        ? trimmed.substring(0, trimmed.length() - extension.length())
        : trimmed;
  }

  /** Everything a filename may not carry, folded away; never escaped, never merely rejected. */
  private static String clean(String name) {
    String safe = UNSAFE.matcher(name).replaceAll("-");
    safe = REPEATED_HYPHENS.matcher(safe).replaceAll("-");
    safe = EDGE_HYPHENS.matcher(safe.strip()).replaceAll("").strip();
    // A name of only dots would be "." or ".." — a directory, not a file.
    return safe.chars().allMatch(c -> c == '.') ? "" : truncate(safe);
  }

  private static String truncate(String value) {
    return value.length() <= MAX_BASE_LENGTH ? value : value.substring(0, MAX_BASE_LENGTH).strip();
  }
}
