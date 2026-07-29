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

import java.util.Locale;

/**
 * The text conversions every export format needs (issue #635).
 *
 * <p>Shared rather than copied, because a status that reads {@code Changes requested} in the
 * spreadsheet and {@code CHANGES_REQUESTED} in the report would be the same defect twice. Comment
 * bodies are markdown; no format renders it as markup today, so the flattening rule lives here too
 * — one place to change when one of them learns to.
 */
final class ExportText {

  private ExportText() {}

  /** {@code CHANGES_REQUESTED} → {@code Changes requested}; null or blank → empty. */
  static String humanize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String lower = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  /**
   * Markdown reduced to one line of plain text: images dropped, links kept as their label, emphasis
   * markers removed, whitespace collapsed.
   */
  static String flatten(String markdown) {
    if (markdown == null) {
      return "";
    }
    return markdown
        .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
        .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
        .replaceAll("[`*_>#~]", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  /**
   * Markdown reduced to plain text while keeping its paragraph breaks — what a report wants, where
   * a spreadsheet cell wants {@link #flatten}.
   */
  static String plainText(String markdown) {
    if (markdown == null) {
      return "";
    }
    return markdown
        .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
        .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
        .replaceAll("(?m)^\\s{0,3}[>#]+\\s?", "")
        .replaceAll("[`*_~]", "")
        .replaceAll("[ \\t]+", " ")
        // Three or more newlines add nothing a reader can see.
        .replaceAll("\n{3,}", "\n\n")
        .strip();
  }

  /** Caps a string at {@code max} characters, marking the cut with an ellipsis. */
  static String truncate(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
