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

/**
 * Control characters in single-line text (issue #909): the C0/C1 control range (which includes CR,
 * LF and TAB) and the Unicode line and paragraph separators.
 *
 * <p>Titles and display names are single-line values that end up in places where a line break
 * changes meaning — mail headers above all, but also file names, log lines and spreadsheet cells.
 * Input boundaries reject them with {@link #contains}; output boundaries that must not fail (a mail
 * subject rendered from a template) neutralise them with {@link #toSpaces}.
 */
public final class ControlCharacters {

  private ControlCharacters() {}

  /** True when {@code value} holds at least one control or line/paragraph-separator character. */
  public static boolean contains(String value) {
    if (value == null) {
      return false;
    }
    return value.codePoints().anyMatch(ControlCharacters::isControl);
  }

  /** {@code value} with every control or separator character replaced by a single space. */
  public static String toSpaces(String value) {
    if (value == null || !contains(value)) {
      return value;
    }
    StringBuilder out = new StringBuilder(value.length());
    value.codePoints().forEach(c -> out.appendCodePoint(isControl(c) ? ' ' : c));
    return out.toString();
  }

  private static boolean isControl(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONTROL
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
