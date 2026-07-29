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

import io.qnop.service.review.AnnotationPosition;
import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The self-contained HTML report (issue #637). */
class HtmlAnnotationRendererTest {

  private final HtmlAnnotationRenderer renderer = new HtmlAnnotationRenderer();

  private static final Instant WHEN = Instant.parse("2026-03-04T10:15:30Z");

  private static AnnotationView view(String title, String body, String author) {
    return new AnnotationView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        author,
        "OPEN",
        "COMMENT",
        "NORMAL",
        "{}",
        "ANCHORED",
        body,
        1,
        null,
        List.of(),
        WHEN,
        WHEN);
  }

  private static CommentView comment(String author, String body) {
    return new CommentView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        author,
        body,
        List.of(),
        WHEN);
  }

  private static String render(HtmlAnnotationRenderer renderer, AnnotationExportModel model) {
    return new String(renderer.render(model), StandardCharsets.UTF_8);
  }

  private static AnnotationExportModel model(String title, String body) {
    return model(title, body, List.of(), Map.of(), Map.of(), null);
  }

  private static AnnotationExportModel model(
      String title,
      String body,
      List<CommentView> thread,
      Map<String, ExportImage> images,
      Map<String, ExportAttachment> files,
      byte[] logo) {
    AnnotationView view = view(title, body, "Mia Member");
    return new AnnotationExportModel(
        title,
        3,
        List.of(
            new AnnotationExportModel.Row(
                "T-1", view, new AnnotationPosition(true, 0, 0.1, 0.1, 0), thread, body)),
        AnnotationExportColumn.all(),
        true,
        logo,
        ExportDateFormat.ISO,
        ZoneOffset.UTC,
        images,
        files);
  }

  private static byte[] png(int width, int height) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
    return out.toByteArray();
  }

  @Test
  @DisplayName("the file references nothing outside itself")
  void isSelfContained() throws Exception {
    String html =
        render(
            renderer,
            model(
                "Vendor agreement",
                "See ![shot.png](/api/v1/documents/d/attachments/a)",
                List.of(),
                Map.of(
                    "/api/v1/documents/d/attachments/a",
                    new ExportImage("shot.png", png(20, 10), "image/png")),
                Map.of(),
                png(40, 20)));

    // It has to open from a mail attachment on a train. Any external reference
    // would break that — and would say when the report was read.
    assertThat(html).doesNotContain("<link ");
    assertThat(html).doesNotContain("src=\"http", "src='http");
    assertThat(html).doesNotContain("@import", "url(http");
    // The stylesheet, the script and both images are all in the file.
    assertThat(html).contains("<style>", "<script>");
    assertThat(html).contains("src=\"data:image/png;base64,");
  }

  @Test
  @DisplayName("a script a commenter wrote is not in the document, escaped or otherwise")
  void refusesInjectedMarkup() {
    String attack = "Before <script>alert(1)</script> and <img src=x onerror=alert(2)> after";

    String html = render(renderer, model("Vendor agreement", attack));

    // The tags are dropped at the parse, so nothing a commenter wrote can become
    // markup. What sat between them is prose and stays visible as prose — the
    // reader sees the payload, the browser never runs it.
    assertThat(html).doesNotContain("<script>alert", "onerror");
    assertThat(html).contains("Before", "alert(1)", "after");
    // The only script element in the file is the one this renderer put there.
    assertThat(html.split("<script").length - 1).isEqualTo(1);
  }

  @Test
  @DisplayName("a hostile review title cannot break out of the markup around it")
  void escapesTitles() {
    String html = render(renderer, model("\"><img src=x onerror=alert(1)>", "A finding"));

    assertThat(html).doesNotContain("<img src=x");
    assertThat(html).contains("&quot;&gt;&lt;img");
  }

  @Test
  @DisplayName("a javascript: link keeps its words and loses its target")
  void refusesUnsafeLinks() {
    String html =
        render(renderer, model("Vendor agreement", "See [click me](javascript:alert(1))"));

    assertThat(html).doesNotContain("href=\"javascript");
    assertThat(html).contains("click me");
  }

  @Test
  @DisplayName("markdown becomes real elements")
  void rendersMarkdown() {
    String html =
        render(
            renderer,
            model(
                "Vendor agreement",
                """
                ## Finding

                A **bold** and *italic* claim.

                - first
                - second

                > quoted

                | A | B |
                | - | - |
                | 1 | 2 |
                """));

    assertThat(html).contains("<strong>bold</strong>", "<em>italic</em>");
    assertThat(html).contains("<ul><li>first</li><li>second</li></ul>");
    assertThat(html).contains("<blockquote>");
    assertThat(html).contains("<table>", "<th>A</th>", "<td>1</td>");
    // A commenter's heading never rises to h1 or h2 — those are the review and
    // the finding.
    assertThat(html).contains("<h4>Finding</h4>");
  }

  @Test
  @DisplayName("the discussion is a details block that is complete without the script")
  void rendersThread() {
    String html =
        render(
            renderer,
            model(
                "Vendor agreement",
                "The finding",
                List.of(comment("Mia Member", "The finding"), comment("Participant 2", "Disagree")),
                Map.of(),
                Map.of(),
                null));

    assertThat(html).contains("<details class=\"thread\">", "Discussion · 1 reply");
    // The reply's text is in the markup, not fetched or built by the script: a
    // client that refuses inline script costs the filter, never the findings.
    assertThat(html).contains("Disagree");
    // The opening comment is the annotation, not a reply.
    assertThat(html.split("The finding").length - 1).isEqualTo(1);
  }

  @Test
  @DisplayName("the filter chips are addressed through the bar, not by carrying a status")
  void scopesTheChipSelector() {
    String html = render(renderer, model("Vendor agreement", "A finding"));

    // The findings carry data-status too — it is what the filter matches on — so
    // an unscoped selector makes every card a filter button, and one click on a
    // card hides every other finding. Caught in a browser, not by a unit test,
    // which is why the selector is asserted here.
    assertThat(html).contains("<article class=\"finding\" data-status=");
    assertThat(html).contains("querySelectorAll('.controls button[data-status]')");
  }

  @Test
  @DisplayName("an empty review is a valid page that says so, and ships no script")
  void handlesEmptyReview() {
    AnnotationExportModel empty =
        new AnnotationExportModel(
            "Vendor agreement",
            3,
            List.of(),
            AnnotationExportColumn.all(),
            false,
            null,
            ExportDateFormat.ISO,
            ZoneOffset.UTC,
            Map.of(),
            Map.of());

    String html = render(renderer, empty);

    assertThat(html).startsWith("<!doctype html>").endsWith("</body></html>");
    assertThat(html).contains("This review has no annotations.");
    // Nothing to filter, so nothing to run.
    assertThat(html).doesNotContain("<script>");
  }

  @Test
  @DisplayName("an image too large to inline degrades to its name")
  void degradesOversizedImages() throws Exception {
    // The budget is what keeps a review of screenshots from becoming a file no
    // mail server will carry — base64 adds a third on top.
    byte[] huge = new byte[9 * 1024 * 1024];
    String url = "/api/v1/documents/d/attachments/a";

    String html =
        render(
            renderer,
            model(
                "Vendor agreement",
                "![big.png](" + url + ")",
                List.of(),
                Map.of(url, new ExportImage("big.png", huge, "image/png")),
                Map.of(),
                null));

    assertThat(html).doesNotContain("base64");
    assertThat(html).contains("[big.png]");
  }
}
