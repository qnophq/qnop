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

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and validates the {@code {{placeholder}}} variable references in a mail template body
 * (issue #141), so the editor can reject unknown variables before they fail at render time.
 *
 * <p>Only ordinary escaped variable tags ({@code {{ name }}}) are treated as references. Comments
 * ({@code {{! … }}}), partials ({@code {{> … }}}), section/inverted markers ({@code {{# }}}, {@code
 * {{/ }}}, {@code {{^ }}}), set-delimiter tags ({@code {{= … }}}) and the unescaped forms ({@code
 * {{{ … }}}}, {@code {{& … }}}) are skipped — they are template structure, not variables to bind.
 */
public final class MailPlaceholderValidator {

  private MailPlaceholderValidator() {}

  /** A triple-stache (skipped), or an ordinary double-stache whose inner text is captured. */
  private static final Pattern TAG =
      Pattern.compile("\\{\\{\\{\\s*[^{}]*?\\s*\\}\\}\\}|\\{\\{\\s*([^{}]*?)\\s*\\}\\}");

  /** Leading characters that mark a tag as structure rather than a bound variable. */
  private static final String NON_VARIABLE_PREFIXES = "!>#/^&=";

  /** The sorted set of variable placeholders referenced in {@code content}. */
  public static SortedSet<String> referencedPlaceholders(String content) {
    SortedSet<String> refs = new TreeSet<>();
    if (content == null || content.isEmpty()) {
      return refs;
    }
    Matcher matcher = TAG.matcher(content);
    while (matcher.find()) {
      String inner = matcher.group(1);
      if (inner == null) {
        continue; // a {{{triple}}} — skipped
      }
      inner = inner.trim();
      if (inner.isEmpty() || NON_VARIABLE_PREFIXES.indexOf(inner.charAt(0)) >= 0) {
        continue;
      }
      refs.add(inner);
    }
    return refs;
  }

  /**
   * The placeholders referenced across the given content pieces that are not in {@code allowed},
   * sorted. Empty when every reference is recognised.
   */
  public static SortedSet<String> unknownPlaceholders(Set<String> allowed, String... contents) {
    SortedSet<String> unknown = new TreeSet<>();
    for (String content : contents) {
      for (String ref : referencedPlaceholders(content)) {
        if (!allowed.contains(ref)) {
          unknown.add(ref);
        }
      }
    }
    return unknown;
  }
}
