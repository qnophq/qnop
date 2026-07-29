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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Parses a comment body into the blocks every export format renders (issue #637).
 *
 * <p>Once, here, rather than per format. Four renderers parsing markdown themselves would be four
 * opinions about what a comment says; this way they differ only in what they can <em>show</em>.
 *
 * <p>The parser is CommonMark with the GFM extensions the review UI also enables — tables,
 * strikethrough, autolinks — so the export reads a comment the way the application does. What came
 * before was a handful of regexes, which could and did disagree with it.
 *
 * <p><strong>Raw HTML is dropped.</strong> CommonMark keeps {@code <script>} and {@code <img
 * onerror=…>} as {@code HtmlBlock}/{@code HtmlInline} nodes, and commonmark's own HTML renderer
 * passes them straight through — which is exactly why it is never used here. Walking the tree
 * ourselves and ignoring those two node types means no format can receive markup a commenter wrote,
 * and the HTML export cannot become a stored-XSS vector that travels by e-mail.
 */
public final class ExportMarkdown {

  /**
   * Shared and thread-safe: a commonmark {@code Parser} is immutable once built, and building one
   * per comment would parse the extension list thousands of times in a large export.
   */
  private static final Parser PARSER =
      Parser.builder()
          .extensions(
              List.of(
                  TablesExtension.create(),
                  StrikethroughExtension.create(),
                  AutolinkExtension.create()))
          .build();

  /** The bullet shown for an unordered item, at every depth. */
  private static final String BULLET = "•";

  private ExportMarkdown() {}

  /** Parses a body; a null or blank one yields no blocks rather than an empty paragraph. */
  public static List<ExportBlock> parse(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return List.of();
    }
    Collector collector = new Collector();
    collector.blocks(PARSER.parse(markdown), 0, 0);
    return List.copyOf(collector.blocks);
  }

  /** Every upload a body points at — images and app-attachment links alike, in order. */
  public static List<String> uploadUrls(String markdown) {
    List<String> urls = new ArrayList<>();
    for (ExportBlock block : parse(markdown)) {
      if (block instanceof ExportBlock.Image image) {
        urls.add(image.url());
      } else if (block instanceof ExportBlock.Attachment attachment) {
        urls.add(attachment.url());
      }
    }
    return urls;
  }

  /**
   * The whole body as plain text.
   *
   * <p>For places that genuinely cannot show structure — a spreadsheet's one-line summary column, a
   * filename. Everything else renders the blocks.
   */
  public static String plainText(String markdown) {
    StringBuilder text = new StringBuilder();
    for (ExportBlock block : parse(markdown)) {
      String line = lineOf(block);
      if (line.isBlank()) {
        continue;
      }
      if (!text.isEmpty()) {
        text.append('\n');
      }
      text.append(line);
    }
    return text.toString();
  }

  private static String lineOf(ExportBlock block) {
    return switch (block) {
      case ExportBlock.Paragraph paragraph -> flatten(paragraph.spans());
      case ExportBlock.Heading heading -> flatten(heading.spans());
      case ExportBlock.ListItem item -> item.marker() + " " + flatten(item.spans());
      case ExportBlock.Code code -> code.text().strip();
      case ExportBlock.Image image -> "[" + label(image.alt(), "image") + "]";
      case ExportBlock.Attachment attachment -> "[" + label(attachment.label(), "attachment") + "]";
      case ExportBlock.TableRow row ->
          String.join(" | ", row.cells().stream().map(ExportMarkdown::flatten).toList());
      case ExportBlock.Divider ignored -> "";
    };
  }

  /** Spans as bare text, emphasis discarded. */
  public static String flatten(List<ExportSpan> spans) {
    StringBuilder text = new StringBuilder();
    for (ExportSpan span : spans) {
      switch (span) {
        case ExportSpan.Text value -> text.append(value.value());
        case ExportSpan.Link link -> text.append(flatten(link.spans()));
        case ExportSpan.Break ignored -> text.append(' ');
      }
    }
    return text.toString().strip();
  }

  private static String label(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** True for a link into this app's own API — those become attachment blocks, not spans. */
  private static boolean isUpload(String destination) {
    return destination != null && destination.startsWith("/api/v1/");
  }

  /** Walks the parsed tree once, flattening quote and list nesting into depths. */
  private static final class Collector {

    private final List<ExportBlock> blocks = new ArrayList<>();

    /** Handles every child of {@code parent} as a block. */
    void blocks(Node parent, int quoteDepth, int listDepth) {
      for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
        block(node, quoteDepth, listDepth);
      }
    }

    private void block(Node node, int quoteDepth, int listDepth) {
      switch (node) {
        case Paragraph paragraph -> paragraph(paragraph, quoteDepth);
        case Heading heading ->
            add(new ExportBlock.Heading(quoteDepth, heading.getLevel(), spans(heading)));
        case BlockQuote quote -> blocks(quote, quoteDepth + 1, listDepth);
        case BulletList list -> list(list, quoteDepth, listDepth, false);
        case OrderedList list -> list(list, quoteDepth, listDepth, true);
        case FencedCodeBlock code -> add(new ExportBlock.Code(quoteDepth, code.getLiteral()));
        case IndentedCodeBlock code -> add(new ExportBlock.Code(quoteDepth, code.getLiteral()));
        case ThematicBreak ignored -> add(new ExportBlock.Divider(quoteDepth));
        case TableBlock table -> table(table, quoteDepth);
        // Raw HTML a commenter wrote: dropped, never rendered and never forwarded
        // as markup. See the class comment.
        case HtmlBlock ignored -> {}
        default -> blocks(node, quoteDepth, listDepth);
      }
    }

    /**
     * A paragraph, with images and upload links lifted out of it.
     *
     * <p>They read as their own thing in every format — a picture, an attachment row — so a
     * paragraph that contains one is split around it rather than swallowing it.
     */
    private void paragraph(Paragraph paragraph, int quoteDepth) {
      List<ExportSpan> pending = new ArrayList<>();
      for (Node node = paragraph.getFirstChild(); node != null; node = node.getNext()) {
        if (node instanceof Image image) {
          flush(pending, quoteDepth);
          add(new ExportBlock.Image(quoteDepth, image.getDestination(), text(image)));
        } else if (node instanceof Link link && isUpload(link.getDestination())) {
          flush(pending, quoteDepth);
          add(new ExportBlock.Attachment(quoteDepth, text(link), link.getDestination()));
        } else {
          pending.addAll(spansOf(node, EnumSet.noneOf(ExportSpan.Style.class)));
        }
      }
      flush(pending, quoteDepth);
    }

    private void flush(List<ExportSpan> pending, int quoteDepth) {
      if (!flatten(pending).isBlank()) {
        add(new ExportBlock.Paragraph(quoteDepth, List.copyOf(pending)));
      }
      pending.clear();
    }

    private void list(Node list, int quoteDepth, int listDepth, boolean ordered) {
      int number = ordered ? startNumber((OrderedList) list) : 0;
      for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
        if (!(item instanceof ListItem)) {
          continue;
        }
        String marker = ordered ? (number++) + "." : BULLET;
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
          if (child instanceof Paragraph paragraph && first) {
            add(new ExportBlock.ListItem(quoteDepth, listDepth, marker, spans(paragraph)));
            first = false;
          } else {
            // Anything after the item's first line — a nested list, a second
            // paragraph — is a block in its own right, one level deeper.
            block(child, quoteDepth, listDepth + 1);
          }
        }
      }
    }

    private static int startNumber(OrderedList list) {
      Integer start = list.getMarkerStartNumber();
      return start == null ? 1 : start;
    }

    private void table(TableBlock table, int quoteDepth) {
      for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
        boolean header = section instanceof TableHead;
        for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
          if (!(row instanceof TableRow)) {
            continue;
          }
          List<List<ExportSpan>> cells = new ArrayList<>();
          for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
            if (cell instanceof TableCell) {
              cells.add(spans(cell));
            }
          }
          add(new ExportBlock.TableRow(quoteDepth, header, cells));
        }
      }
    }

    private void add(ExportBlock block) {
      blocks.add(block);
    }

    /** Every inline child of {@code parent}, with no emphasis in force to begin with. */
    private static List<ExportSpan> spans(Node parent) {
      List<ExportSpan> spans = new ArrayList<>();
      for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
        spans.addAll(spansOf(node, EnumSet.noneOf(ExportSpan.Style.class)));
      }
      return spans;
    }

    /** One inline node, carrying the emphasis that surrounds it down into its text. */
    private static List<ExportSpan> spansOf(Node node, Set<ExportSpan.Style> styles) {
      return switch (node) {
        case Text text -> List.of(new ExportSpan.Text(text.getLiteral(), styles));
        case Code code ->
            List.of(new ExportSpan.Text(code.getLiteral(), with(styles, ExportSpan.Style.CODE)));
        case StrongEmphasis strong -> children(strong, with(styles, ExportSpan.Style.BOLD));
        case Emphasis emphasis -> children(emphasis, with(styles, ExportSpan.Style.ITALIC));
        case Strikethrough struck -> children(struck, with(styles, ExportSpan.Style.STRIKETHROUGH));
        case Link link ->
            List.of(new ExportSpan.Link(link.getDestination(), children(link, styles)));
        case Image image ->
            // An image inside a sentence rather than alone in its paragraph. It is
            // already lifted out where that is possible; here it can only be named.
            List.of(new ExportSpan.Text("[" + label(text(image), "image") + "]", styles));
        case SoftLineBreak ignored -> List.of(new ExportSpan.Break());
        case HardLineBreak ignored -> List.of(new ExportSpan.Break());
        // Raw inline HTML: dropped, like its block counterpart.
        case HtmlInline ignored -> List.of();
        default -> children(node, styles);
      };
    }

    private static List<ExportSpan> children(Node parent, Set<ExportSpan.Style> styles) {
      List<ExportSpan> spans = new ArrayList<>();
      for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
        spans.addAll(spansOf(node, styles));
      }
      return spans;
    }

    private static Set<ExportSpan.Style> with(
        Set<ExportSpan.Style> styles, ExportSpan.Style added) {
      Set<ExportSpan.Style> combined = EnumSet.noneOf(ExportSpan.Style.class);
      combined.addAll(styles);
      combined.add(added);
      return combined;
    }
  }

  /** A node's descendant text, for alt text and link labels. */
  private static String text(Node parent) {
    StringBuilder value = new StringBuilder();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
      if (node instanceof Text text) {
        value.append(text.getLiteral());
      } else if (node instanceof Code code) {
        value.append(code.getLiteral());
      } else {
        value.append(text(node));
      }
    }
    return value.toString().strip();
  }

  /** Whether a URL may be linked at all — anything but http(s) is refused. */
  public static boolean isSafeHref(String href) {
    if (href == null || href.isBlank()) {
      return false;
    }
    String scheme = href.strip().toLowerCase(Locale.ROOT);
    // A `javascript:` or `data:` target in a comment would be one click from
    // execution in the HTML export; relative links are this app's own.
    return scheme.startsWith("http://") || scheme.startsWith("https://") || scheme.startsWith("/");
  }
}
