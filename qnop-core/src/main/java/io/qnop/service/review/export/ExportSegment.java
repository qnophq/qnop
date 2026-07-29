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

  /**
   * A file attached to the annotation or comment, referenced by a plain link.
   *
   * <p>{@code MarkdownComposer} writes image syntax for images and link syntax for everything else,
   * so a PDF or a spreadsheet arrives here rather than as an {@link Image}.
   *
   * @param label the link text, which for an upload is the original filename
   * @param url the Markdown target, pointing at this app's attachment endpoint
   */
  record Attachment(String label, String url) implements ExportSegment {}

  /** {@code ![alt](target)} — the form {@code MarkdownComposer} writes for an upload. */
  Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]*)[^)]*\\)");

  /**
   * {@code [label](/api/v1/…)} — a link at this app's own API, i.e. an upload.
   *
   * <p>Deliberately not every link: an external URL someone quoted in prose is part of the
   * sentence, and turning it into an attachment row would misrepresent what was written.
   */
  Pattern ATTACHMENT = Pattern.compile("(?<!!)\\[([^\\]]*)\\]\\((/api/v1/[^)\\s]*)[^)]*\\)");

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
    Matcher images = IMAGE.matcher(markdown);
    Matcher files = ATTACHMENT.matcher(markdown);
    boolean hasImage = images.find();
    boolean hasFile = files.find();
    int cursor = 0;

    // One pass over both patterns, taking whichever match comes first, so the
    // segments keep the order the author wrote them in.
    while (hasImage || hasFile) {
      boolean imageNext = hasImage && (!hasFile || images.start() < files.start());
      Matcher next = imageNext ? images : files;
      addText(segments, markdown.substring(cursor, next.start()));
      segments.add(
          imageNext
              ? new Image(next.group(1), next.group(2))
              : new Attachment(next.group(1), next.group(2)));
      cursor = next.end();
      if (hasImage && images.start() < cursor) {
        hasImage = images.find(cursor);
      }
      if (hasFile && files.start() < cursor) {
        hasFile = files.find(cursor);
      }
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

  /** Every upload a body points at — images and files alike, in order. */
  static List<String> uploadUrls(String markdown) {
    return split(markdown).stream()
        .map(
            segment ->
                segment instanceof Image image
                    ? image.url()
                    : segment instanceof Attachment file ? file.url() : null)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /** Every attachment target in a body, in order, without duplicates removed. */
  static List<String> attachmentUrls(String markdown) {
    return split(markdown).stream()
        .filter(Attachment.class::isInstance)
        .map(segment -> ((Attachment) segment).url())
        .toList();
  }

  private static void addText(List<ExportSegment> segments, String raw) {
    String text = ExportText.plainText(raw);
    if (!text.isBlank()) {
      segments.add(new Text(text));
    }
  }
}
