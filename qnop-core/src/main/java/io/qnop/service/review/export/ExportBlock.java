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

/**
 * One block of a comment body, as every export format sees it (issue #637).
 *
 * <p>A flat sequence rather than a tree. Markdown nests — a list inside a quote inside a list — but
 * none of the outputs do: Word sets indentation and a border on a paragraph, a spreadsheet cell has
 * lines, and HTML is the only one that could nest and gains nothing by it here. Carrying the
 * nesting as two integers, {@link #quoteDepth()} and a list depth, turns every renderer into a loop
 * instead of a recursive walk, and no format loses anything it could have shown.
 */
public sealed interface ExportBlock {

  /** How many levels of block quote this block sits inside; 0 for ordinary content. */
  int quoteDepth();

  /** Ordinary prose. */
  record Paragraph(int quoteDepth, List<ExportSpan> spans) implements ExportBlock {
    public Paragraph {
      spans = List.copyOf(spans);
    }
  }

  /** A heading, {@code level} 1–6 as written. */
  record Heading(int quoteDepth, int level, List<ExportSpan> spans) implements ExportBlock {
    public Heading {
      spans = List.copyOf(spans);
    }
  }

  /**
   * One item of a list.
   *
   * @param depth nesting level, 0 for a top-level item
   * @param marker what precedes the text — a bullet, or the number as written
   */
  record ListItem(int quoteDepth, int depth, String marker, List<ExportSpan> spans)
      implements ExportBlock {
    public ListItem {
      spans = List.copyOf(spans);
    }
  }

  /** A fenced or indented code block, verbatim. */
  record Code(int quoteDepth, String text) implements ExportBlock {}

  /**
   * An image, promoted out of its paragraph.
   *
   * <p>Markdown treats an image as inline; every format here places it as its own block, which is
   * also what the exports already did before markdown was parsed at all.
   */
  record Image(int quoteDepth, String url, String alt) implements ExportBlock {}

  /** A link to one of this app's own uploads, promoted out of its paragraph. */
  record Attachment(int quoteDepth, String label, String url) implements ExportBlock {}

  /** One row of a GFM table; {@code header} marks the row above the delimiter line. */
  record TableRow(int quoteDepth, boolean header, List<List<ExportSpan>> cells)
      implements ExportBlock {
    public TableRow {
      cells = cells.stream().map(List::copyOf).toList();
    }
  }

  /** A thematic break. */
  record Divider(int quoteDepth) implements ExportBlock {}
}
