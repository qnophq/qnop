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

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.review.export.ExportSpan.Style;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared markdown parse every export format renders from (issue #637). */
class ExportMarkdownTest {

  private static List<ExportBlock> parse(String markdown) {
    return ExportMarkdown.parse(markdown);
  }

  /** The spans of the first block, whatever kind it is. */
  private static List<ExportSpan> spansOf(ExportBlock block) {
    return switch (block) {
      case ExportBlock.Paragraph paragraph -> paragraph.spans();
      case ExportBlock.Heading heading -> heading.spans();
      case ExportBlock.ListItem item -> item.spans();
      default -> List.of();
    };
  }

  @Test
  @DisplayName("emphasis survives as styles on the run it applied to")
  void carriesEmphasis() {
    List<ExportSpan> spans =
        spansOf(parse("A **bold** and *italic* and ~~struck~~ line").getFirst());

    assertThat(styleOf(spans, "bold")).containsExactly(Style.BOLD);
    assertThat(styleOf(spans, "italic")).containsExactly(Style.ITALIC);
    assertThat(styleOf(spans, "struck")).containsExactly(Style.STRIKETHROUGH);
    // The prose around them carries nothing.
    assertThat(styleOf(spans, "A ")).isEmpty();
  }

  /** The styles in force on the run whose text is exactly {@code value}. */
  private static java.util.Set<Style> styleOf(List<ExportSpan> spans, String value) {
    return spans.stream()
        .filter(ExportSpan.Text.class::isInstance)
        .map(ExportSpan.Text.class::cast)
        .filter(text -> text.value().equals(value))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no run with text " + value))
        .styles();
  }

  @Test
  @DisplayName("nested emphasis collapses into one run carrying both styles")
  void collapsesNestedEmphasis() {
    // Every format applies emphasis as properties on a run, so nesting is not a
    // shape any of them can take — flattening loses nothing.
    List<ExportSpan> spans = spansOf(parse("***both***").getFirst());

    assertThat(spans).hasSize(1);
    ExportSpan.Text text = (ExportSpan.Text) spans.getFirst();
    assertThat(text.value()).isEqualTo("both");
    assertThat(text.styles()).containsExactlyInAnyOrder(Style.BOLD, Style.ITALIC);
  }

  @Test
  @DisplayName("headings, lists, quotes and code arrive as their own blocks")
  void recognisesBlockStructure() {
    List<ExportBlock> blocks =
        parse(
            """
            ## Finding

            - first
            - second

            > quoted

            ```
            code()
            ```
            """);

    assertThat(blocks).hasAtLeastOneElementOfType(ExportBlock.Heading.class);
    assertThat(blocks.stream().filter(ExportBlock.ListItem.class::isInstance)).hasSize(2);
    assertThat(blocks)
        .anySatisfy(block -> assertThat(block.quoteDepth()).isEqualTo(1))
        .anySatisfy(block -> assertThat(block).isInstanceOf(ExportBlock.Code.class));
    assertThat(((ExportBlock.Heading) blocks.getFirst()).level()).isEqualTo(2);
  }

  @Test
  @DisplayName("an ordered list keeps its numbering")
  void keepsOrderedNumbers() {
    List<ExportBlock> blocks = parse("3. third\n4. fourth");

    assertThat(blocks)
        .filteredOn(ExportBlock.ListItem.class::isInstance)
        .extracting(block -> ((ExportBlock.ListItem) block).marker())
        .containsExactly("3.", "4.");
  }

  @Test
  @DisplayName("images and upload links are lifted out of the paragraph around them")
  void liftsUploadsOutOfProse() {
    String url = "/api/v1/documents/d/attachments/a";
    List<ExportBlock> blocks =
        parse("Look at ![shot.png](" + url + ") and [f.pdf](" + url + ") too");

    // Both read as their own thing in every format — a picture, an attachment row
    // — so the sentence is split around them rather than swallowing them.
    assertThat(blocks).hasAtLeastOneElementOfType(ExportBlock.Image.class);
    assertThat(blocks).hasAtLeastOneElementOfType(ExportBlock.Attachment.class);
    assertThat(ExportMarkdown.uploadUrls("![a](" + url + ")")).containsExactly(url);
  }

  @Test
  @DisplayName("an ordinary link stays inside the sentence")
  void keepsProseLinksInline() {
    List<ExportSpan> spans =
        spansOf(parse("See [the spec](https://example.com/spec) for more").getFirst());

    assertThat(spans).hasAtLeastOneElementOfType(ExportSpan.Link.class);
    ExportSpan.Link link =
        (ExportSpan.Link)
            spans.stream().filter(ExportSpan.Link.class::isInstance).findFirst().orElseThrow();
    assertThat(link.href()).isEqualTo("https://example.com/spec");
  }

  @Test
  @DisplayName("raw HTML a commenter wrote is dropped, never carried as markup")
  void dropsRawHtml() {
    List<ExportBlock> blocks =
        parse("Before\n\n<script>alert(1)</script>\n\nAfter <img onerror=\"x\"> end");

    String text =
        ExportMarkdown.plainText("Before\n\n<script>alert(1)</script>\n\nAfter <b>x</b> end");

    // The whole reason commonmark's own HTML renderer is never used: it would
    // pass these through. Nothing downstream can receive markup a user wrote.
    assertThat(blocks).isNotEmpty();
    assertThat(text).doesNotContain("<script", "<img", "<b>", "onerror");
    assertThat(text).contains("Before", "After");
  }

  @Test
  @DisplayName("only http(s) and app-relative targets may be linked")
  void refusesDangerousHrefs() {
    assertThat(ExportMarkdown.isSafeHref("https://example.com")).isTrue();
    assertThat(ExportMarkdown.isSafeHref("http://example.com")).isTrue();
    assertThat(ExportMarkdown.isSafeHref("/api/v1/documents/d/attachments/a")).isTrue();
    // One click from execution in a document that opens in a browser.
    assertThat(ExportMarkdown.isSafeHref("javascript:alert(1)")).isFalse();
    assertThat(ExportMarkdown.isSafeHref("JavaScript:alert(1)")).isFalse();
    assertThat(ExportMarkdown.isSafeHref("data:text/html;base64,PHNjcmlwdD4=")).isFalse();
    assertThat(ExportMarkdown.isSafeHref(null)).isFalse();
  }

  @Test
  @DisplayName("a GFM table arrives as rows, with its header marked")
  void readsTables() {
    List<ExportBlock> blocks = parse("| A | B |\n| - | - |\n| 1 | 2 |");

    assertThat(blocks)
        .filteredOn(ExportBlock.TableRow.class::isInstance)
        .extracting(block -> ((ExportBlock.TableRow) block).header())
        .containsExactly(true, false);
  }

  @Test
  @DisplayName("an empty body yields no blocks at all")
  void handlesEmptyInput() {
    assertThat(parse(null)).isEmpty();
    assertThat(parse("   ")).isEmpty();
    assertThat(ExportMarkdown.plainText(null)).isEmpty();
  }
}
