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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A comment body split into the parts an export renders differently (issue #635 follow-up).
 *
 * <p>Bodies are Markdown, and a screenshot pasted into a review is often the whole point of the
 * comment — "look at this line" with the line in a picture. The flattening that came before dropped
 * image references entirely, so those comments exported as a sentence that referred to nothing.
 *
 * <p>Splitting rather than stripping lets each format decide: a document embeds the picture, a
 * spreadsheet cannot and names it instead. Neither silently loses it.
 */
public sealed interface ExportSegment {

  /** Prose, already flattened to plain text. */
  record Text(String value) implements ExportSegment {}

  /**
   * An inline image.
   *
   * @param alt the Markdown alt text, which for an upload is the original filename
   * @param url the Markdown target, which for an upload points at this app's attachment endpoint
   */
  record Image(String alt, String url) implements ExportSegment {}

  /** {@code ![alt](target)} — the form {@code MarkdownComposer} writes for an upload. */
  Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]*)[^)]*\\)");

  /**
   * Splits a body into prose and images, in reading order.
   *
   * <p>Text runs keep the paragraph breaks {@link ExportText#plainText} produces; an image between
   * two paragraphs comes out as its own segment, so a renderer can place it where the author put it
   * rather than collecting pictures at the end.
   */
  static List<ExportSegment> split(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return List.of();
    }
    List<ExportSegment> segments = new ArrayList<>();
    Matcher matcher = IMAGE.matcher(markdown);
    int cursor = 0;
    while (matcher.find()) {
      addText(segments, markdown.substring(cursor, matcher.start()));
      segments.add(new Image(matcher.group(1), matcher.group(2)));
      cursor = matcher.end();
    }
    addText(segments, markdown.substring(cursor));
    return List.copyOf(segments);
  }

  /** Every image target in a body, in order, without duplicates removed. */
  static List<String> imageUrls(String markdown) {
    return split(markdown).stream()
        .filter(Image.class::isInstance)
        .map(segment -> ((Image) segment).url())
        .toList();
  }

  private static void addText(List<ExportSegment> segments, String raw) {
    String text = ExportText.plainText(raw);
    if (!text.isBlank()) {
      segments.add(new Text(text));
    }
  }
}
