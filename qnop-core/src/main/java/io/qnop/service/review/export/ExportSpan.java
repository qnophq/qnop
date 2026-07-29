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

import java.util.List;
import java.util.Set;

/**
 * A run of inline content inside one block (issue #637).
 *
 * <p>Flat on purpose: markdown nests emphasis arbitrarily, and every format here renders emphasis
 * by setting properties on a run of characters rather than by nesting anything. Collapsing {@code
 * **bold *and italic***} into one span carrying both styles is therefore not a loss — it is the
 * shape the outputs actually take.
 */
public sealed interface ExportSpan {

  /** The character-level properties a format can apply to a run. */
  enum Style {
    BOLD,
    ITALIC,
    STRIKETHROUGH,
    CODE
  }

  /** Literal text, with whatever emphasis was in force where it appeared. */
  record Text(String value, Set<Style> styles) implements ExportSpan {
    public Text {
      styles = Set.copyOf(styles);
    }

    public boolean has(Style style) {
      return styles.contains(style);
    }
  }

  /**
   * A link that is part of a sentence.
   *
   * <p>Links to this app's own uploads are <em>not</em> spans — they become their own block, so a
   * reader sees an attachment row rather than a word in a paragraph. That is the behaviour the
   * exports already had, kept.
   */
  record Link(String href, List<ExportSpan> spans) implements ExportSpan {
    public Link {
      spans = List.copyOf(spans);
    }
  }

  /** An explicit line break inside a paragraph. */
  record Break() implements ExportSpan {}
}
