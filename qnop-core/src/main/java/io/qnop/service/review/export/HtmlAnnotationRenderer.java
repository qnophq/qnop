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

import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders the export as one self-contained HTML file (issue #637).
 *
 * <p>One file and nothing else: the stylesheet and the script are inlined, images travel as {@code
 * data:} URIs, and no font is fetched. It has to open from a mail attachment on a train — an
 * external reference would break that promise and, incidentally, tell someone when the report was
 * read.
 *
 * <p>It is also the only export that can be <em>interactive</em>, which is the reason it exists
 * beside the PDF: a reader facing eighty findings can filter them. The script does that and nothing
 * more, and the document is complete without it — a client that refuses inline script costs the
 * filter, never the findings.
 *
 * <p><strong>Every user string is escaped by construction</strong> ({@link HtmlWriter}), raw markup
 * comes only from compile-time constants, and markup a commenter wrote was already dropped at the
 * parse (ADR-0052). Those three together are why a review cannot be turned into a document that
 * runs code on whoever opens it.
 */
@Component
public class HtmlAnnotationRenderer implements AnnotationExportRenderer {

  /**
   * How many bytes of image this format will inline.
   *
   * <p>Base64 adds a third, so the resolver's budget would become a file no mail server accepts.
   * Beyond this an image degrades to its name, which is what an unresolvable one already does.
   */
  private static final int IMAGE_BUDGET_BYTES = 8 * 1024 * 1024;

  @Override
  public AnnotationExportFormat format() {
    return AnnotationExportFormat.HTML;
  }

  @Override
  public byte[] render(AnnotationExportModel model) {
    HtmlWriter html = new HtmlWriter();
    Budget budget = new Budget(IMAGE_BUDGET_BYTES);

    html.raw("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        .raw("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .raw("<title>");
    html.text(model.documentTitle() + " — annotations");
    html.raw("</title><style>").raw(HtmlReportStyle.CSS).raw("</style></head><body>");
    html.raw("<main class=\"sheet\">");

    writeMasthead(html, model, budget);
    if (model.rows().isEmpty()) {
      html.raw("<p class=\"empty\">This review has no annotations.</p>");
    } else {
      writeControls(html, model);
      for (AnnotationExportModel.Row row : model.rows()) {
        writeFinding(html, model, row, budget);
      }
    }

    html.raw("</main>");
    if (!model.rows().isEmpty()) {
      html.raw("<script>").raw(HtmlReportStyle.JS).raw("</script>");
    }
    html.raw("</body></html>");
    return html.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void writeMasthead(HtmlWriter html, AnnotationExportModel model, Budget budget) {
    html.raw("<header class=\"masthead\"><div>");
    html.raw("<h1>").text(model.documentTitle()).raw("</h1>");
    html.raw("<p class=\"sub\">");
    List<String> parts = new ArrayList<>();
    parts.add("Annotation report");
    if (model.versionNumber() != null) {
      parts.add("version " + model.versionNumber());
    }
    parts.add(model.rows().size() + (model.rows().size() == 1 ? " annotation" : " annotations"));
    html.text(String.join(" · ", parts));
    html.raw("</p></div>");
    if (model.hasLogo() && budget.take(model.logoPng().length)) {
      html.raw("<img").attr("src", dataUri("image/png", model.logoPng())).attr("alt", "").raw(">");
    }
    html.raw("</header>");
  }

  /**
   * The filter bar.
   *
   * <p>Rendered as real form controls with no handlers attached in markup — the script wires them
   * up. Without it they simply do nothing, which is why the findings below are never hidden by
   * default.
   */
  private void writeControls(HtmlWriter html, AnnotationExportModel model) {
    html.raw("<div class=\"controls\">");
    html.raw("<input id=\"q\" type=\"search\"")
        .attr("placeholder", "Search these findings…")
        .attr("aria-label", "Search findings")
        .raw(">");
    html.raw("<button type=\"button\" data-status=\"all\" aria-pressed=\"true\">All</button>");
    html.raw("<button type=\"button\" data-status=\"Open\" aria-pressed=\"false\">Open</button>");
    html.raw(
        "<button type=\"button\" data-status=\"Resolved\" aria-pressed=\"false\">Resolved</button>");
    html.raw("<button type=\"button\" id=\"expand\" aria-pressed=\"false\">Discussions</button>");
    html.raw("<span class=\"count\" id=\"count\">");
    html.text(model.rows().size() + " of " + model.rows().size());
    html.raw("</span></div>");
  }

  private void writeFinding(
      HtmlWriter html, AnnotationExportModel model, AnnotationExportModel.Row row, Budget budget) {
    AnnotationView view = row.view();
    html.raw("<article class=\"finding\"")
        .attr("data-status", ExportText.humanize(view.status()))
        .raw(">");

    html.raw("<div class=\"key\"><h2>");
    html.text(headingText(model, row));
    html.raw("</h2></div>");

    html.raw("<p class=\"facts\">");
    for (String fact : facts(model, row)) {
      html.raw("<span>").text(fact).raw("</span>");
    }
    html.raw("</p>");

    if (model.has(AnnotationExportColumn.SUMMARY)) {
      html.raw("<div class=\"body\">");
      writeBlocks(html, model, ExportMarkdown.parse(row.openingComment()), budget);
      html.raw("</div>");
    }

    writeThread(html, model, row, budget);
    html.raw("</article>");
  }

  private void writeThread(
      HtmlWriter html, AnnotationExportModel model, AnnotationExportModel.Row row, Budget budget) {
    List<CommentView> replies = row.replyComments();
    if (replies.isEmpty()) {
      return;
    }
    String finder = row.view().authorDisplayName();
    html.raw("<details class=\"thread\"><summary>");
    html.text("Discussion · " + replies.size() + (replies.size() == 1 ? " reply" : " replies"));
    html.raw("</summary>");
    for (CommentView comment : replies) {
      boolean byFinder = finder != null && finder.equals(comment.authorDisplayName());
      html.raw(byFinder ? "<div class=\"turn by-author\">" : "<div class=\"turn\">");
      html.raw("<div><span class=\"who\">");
      html.text(
          comment.authorDisplayName() == null || comment.authorDisplayName().isBlank()
              ? "Unknown"
              : comment.authorDisplayName());
      html.raw("</span><span class=\"when\">");
      html.text(model.formatTimestamp(comment.createdAt()));
      html.raw("</span></div><div class=\"body\">");
      writeBlocks(html, model, ExportMarkdown.parse(comment.body()), budget);
      html.raw("</div></div>");
    }
    html.raw("</details>");
  }

  /** The shared blocks as elements; consecutive list items and table rows are grouped. */
  private void writeBlocks(
      HtmlWriter html, AnnotationExportModel model, List<ExportBlock> blocks, Budget budget) {
    int quoted = 0;
    for (int index = 0; index < blocks.size(); index++) {
      ExportBlock block = blocks.get(index);
      quoted = adjustQuote(html, quoted, block.quoteDepth());

      if (block instanceof ExportBlock.ListItem) {
        index = writeList(html, blocks, index) - 1;
        continue;
      }
      if (block instanceof ExportBlock.TableRow) {
        index = writeTable(html, blocks, index) - 1;
        continue;
      }
      switch (block) {
        case ExportBlock.Paragraph paragraph -> {
          html.raw("<p>");
          writeSpans(html, paragraph.spans());
          html.raw("</p>");
        }
        case ExportBlock.Heading heading -> {
          // Never above h3: h1 is the review and h2 the finding, and a commenter's
          // heading is neither.
          String tag = "h" + Math.min(6, 2 + Math.max(1, heading.level()));
          html.open(tag);
          writeSpans(html, heading.spans());
          html.close(tag);
        }
        case ExportBlock.Code code -> {
          html.raw("<pre><code>").text(code.text().stripTrailing()).raw("</code></pre>");
        }
        case ExportBlock.Image image -> writeImage(html, model, image, budget);
        case ExportBlock.Attachment file -> writeAttachment(html, model, file);
        case ExportBlock.Divider ignored -> html.raw("<hr>");
        case ExportBlock.ListItem ignored -> {}
        case ExportBlock.TableRow ignored -> {}
      }
    }
    adjustQuote(html, quoted, 0);
  }

  /** Opens or closes blockquotes so the nesting matches the block's depth. */
  private int adjustQuote(HtmlWriter html, int current, int wanted) {
    for (int depth = current; depth < wanted; depth++) {
      html.raw("<blockquote>");
    }
    for (int depth = current; depth > wanted; depth--) {
      html.raw("</blockquote>");
    }
    return wanted;
  }

  /** A run of list items as one list; returns the index after the run. */
  private int writeList(HtmlWriter html, List<ExportBlock> blocks, int from) {
    ExportBlock.ListItem first = (ExportBlock.ListItem) blocks.get(from);
    boolean ordered = !first.marker().equals("•");
    String tag = ordered ? "ol" : "ul";
    html.open(tag);
    int index = from;
    while (index < blocks.size() && blocks.get(index) instanceof ExportBlock.ListItem item) {
      html.raw("<li>");
      writeSpans(html, item.spans());
      html.raw("</li>");
      index++;
    }
    html.close(tag);
    return index;
  }

  /** A run of table rows as one table; returns the index after the run. */
  private int writeTable(HtmlWriter html, List<ExportBlock> blocks, int from) {
    html.raw("<table>");
    int index = from;
    while (index < blocks.size() && blocks.get(index) instanceof ExportBlock.TableRow row) {
      html.raw("<tr>");
      String cell = row.header() ? "th" : "td";
      for (List<ExportSpan> spans : row.cells()) {
        html.open(cell);
        writeSpans(html, spans);
        html.close(cell);
      }
      html.raw("</tr>");
      index++;
    }
    html.raw("</table>");
    return index;
  }

  private void writeSpans(HtmlWriter html, List<ExportSpan> spans) {
    for (ExportSpan span : spans) {
      switch (span) {
        case ExportSpan.Break ignored -> html.raw("<br>");
        case ExportSpan.Link link -> {
          if (ExportMarkdown.isSafeHref(link.href())) {
            html.raw("<a").attr("href", link.href()).attr("rel", "noopener noreferrer").raw(">");
            writeSpans(html, link.spans());
            html.raw("</a>");
          } else {
            // A javascript: or data: target keeps its words and loses its link.
            writeSpans(html, link.spans());
          }
        }
        case ExportSpan.Text text -> {
          List<String> tags = new ArrayList<>();
          if (text.has(ExportSpan.Style.BOLD)) {
            tags.add("strong");
          }
          if (text.has(ExportSpan.Style.ITALIC)) {
            tags.add("em");
          }
          if (text.has(ExportSpan.Style.STRIKETHROUGH)) {
            tags.add("s");
          }
          if (text.has(ExportSpan.Style.CODE)) {
            tags.add("code");
          }
          tags.forEach(tag -> html.open(tag));
          html.text(text.value());
          for (int index = tags.size() - 1; index >= 0; index--) {
            html.close(tags.get(index));
          }
        }
      }
    }
  }

  private void writeImage(
      HtmlWriter html, AnnotationExportModel model, ExportBlock.Image image, Budget budget) {
    ExportImage resolved = model.image(image.url());
    String alt = image.alt() == null || image.alt().isBlank() ? "image" : image.alt();
    if (resolved == null || !resolved.hasContent() || !budget.take(resolved.content().length)) {
      html.raw("<p class=\"file\">").text("[" + alt + "]").raw("</p>");
      return;
    }
    html.raw("<img")
        .attr("src", dataUri(resolved.contentType(), resolved.content()))
        .attr("alt", alt)
        .raw(">");
  }

  private void writeAttachment(
      HtmlWriter html, AnnotationExportModel model, ExportBlock.Attachment file) {
    ExportAttachment resolved = model.attachment(file.url());
    String label =
        resolved != null && resolved.fileName() != null && !resolved.fileName().isBlank()
            ? resolved.fileName()
            : (file.label() == null || file.label().isBlank() ? "attachment" : file.label());

    html.raw("<p class=\"file\">📎 ");
    boolean linkable =
        resolved != null && resolved.href() != null && ExportMarkdown.isSafeHref(resolved.href());
    if (linkable) {
      html.raw("<a").attr("href", resolved.href()).attr("rel", "noopener noreferrer").raw(">");
      html.text(label);
      html.raw("</a><span class=\"meta\">").text(resolved.describe()).raw("</span>");
    } else {
      html.text(label);
    }
    html.raw("</p>");
  }

  /** {@code T-3 · Page 2}, the same shorthand every other format uses. */
  private String headingText(AnnotationExportModel model, AnnotationExportModel.Row row) {
    StringBuilder text = new StringBuilder();
    if (model.has(AnnotationExportColumn.TASK_KEY)) {
      text.append(row.taskKey());
    }
    if (model.has(AnnotationExportColumn.PAGE) && row.page() != null) {
      if (!text.isEmpty()) {
        text.append(" · ");
      }
      text.append("Page ").append(row.page());
    }
    return text.isEmpty() ? "Annotation" : text.toString();
  }

  private List<String> facts(AnnotationExportModel model, AnnotationExportModel.Row row) {
    AnnotationView view = row.view();
    List<String> facts = new ArrayList<>();
    add(facts, model, AnnotationExportColumn.STATUS, "Status", ExportText.humanize(view.status()));
    add(facts, model, AnnotationExportColumn.TYPE, "Type", ExportText.humanize(view.type()));
    add(
        facts,
        model,
        AnnotationExportColumn.PRIORITY,
        "Priority",
        ExportText.humanize(view.priority()));
    add(facts, model, AnnotationExportColumn.AUTHOR, "Author", view.authorDisplayName());
    add(
        facts,
        model,
        AnnotationExportColumn.PLACEMENT,
        "Placement",
        ExportText.humanize(view.placementStatus()));
    add(facts, model, AnnotationExportColumn.REPLIES, "Replies", String.valueOf(row.replies()));
    add(
        facts,
        model,
        AnnotationExportColumn.CREATED,
        "Created",
        model.formatTimestamp(view.createdAt()));
    add(
        facts,
        model,
        AnnotationExportColumn.UPDATED,
        "Updated",
        model.formatTimestamp(view.updatedAt()));
    return facts;
  }

  private static void add(
      List<String> facts,
      AnnotationExportModel model,
      AnnotationExportColumn column,
      String label,
      String value) {
    if (model.has(column) && value != null && !value.isBlank()) {
      facts.add(label + ": " + value);
    }
  }

  private static String dataUri(String contentType, byte[] content) {
    return "data:"
        + (contentType == null || contentType.isBlank() ? "image/png" : contentType)
        + ";base64,"
        + Base64.getEncoder().encodeToString(content);
  }

  /** What is left of the inlining allowance. */
  private static final class Budget {
    private int remaining;

    Budget(int bytes) {
      this.remaining = bytes;
    }

    boolean take(int bytes) {
      if (bytes > remaining) {
        return false;
      }
      remaining -= bytes;
      return true;
    }
  }
}
