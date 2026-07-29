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

/**
 * Builds the HTML export, escaping by construction (issue #637).
 *
 * <p>The export is a document a browser executes, assembled from review titles, comment text and
 * filenames — all of it written by users. Getting one interpolation wrong turns it into stored XSS
 * that travels by e-mail, so escaping cannot be something a caller remembers to do.
 *
 * <p>Hence the shape of this class: {@link #text} and {@link #attr} are the <em>only</em> ways
 * content enters the document, and both always escape. Raw markup has one entrance, {@link #raw},
 * and it takes a compile-time constant — the stylesheet, the script, a tag this class itself emits.
 * A user string cannot reach it, because a user string is never a constant.
 */
final class HtmlWriter {

  private final StringBuilder out = new StringBuilder(8192);

  /**
   * Appends markup verbatim.
   *
   * <p>For the document's own scaffolding only. Every call site passes a literal; none passes
   * anything derived from the model.
   */
  HtmlWriter raw(String markup) {
    out.append(markup);
    return this;
  }

  /** Appends text, escaped. The only way content becomes part of the document. */
  HtmlWriter text(String value) {
    return raw(escape(value));
  }

  /** Opens a tag with an optional class. */
  HtmlWriter open(String tag, String className) {
    raw("<").raw(tag);
    if (className != null) {
      raw(" class=\"").raw(escape(className)).raw("\"");
    }
    return raw(">");
  }

  HtmlWriter open(String tag) {
    return open(tag, null);
  }

  HtmlWriter close(String tag) {
    return raw("</").raw(tag).raw(">");
  }

  /** An attribute, escaped for a double-quoted value. */
  HtmlWriter attr(String name, String value) {
    return raw(" ").raw(name).raw("=\"").raw(escape(value)).raw("\"");
  }

  @Override
  public String toString() {
    return out.toString();
  }

  /**
   * Escapes for both text and quoted-attribute contexts.
   *
   * <p>All five, including both quote characters: an attribute value is where a single missing
   * escape lets an author close the attribute and add another one. {@code &} goes first, or the
   * escapes would escape each other.
   */
  static String escape(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }
}
