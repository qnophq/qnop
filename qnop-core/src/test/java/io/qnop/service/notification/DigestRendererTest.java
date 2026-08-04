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
package io.qnop.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.entity.NotificationType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The sentence the digest actually says (issue #680). */
class DigestRendererTest {

  private static final UUID DOC = UUID.randomUUID();

  @Test
  @DisplayName("counts read as a summary, in a fixed order, with singulars where it is one")
  void readsAsASummary() {
    String plain =
        DigestRenderer.plain(
            content(
                Map.of(
                    NotificationType.COMMENT_ADDED, 7,
                    NotificationType.ANNOTATION_CREATED, 3,
                    NotificationType.VERSION_UPLOADED, 1)),
            Map.of(DOC, target("Vendor Agreement")),
            NAMES,
            ZONE);

    // Annotations before comments before versions, whatever order they arrived in,
    // and the way back on its own line.
    assertThat(plain)
        .startsWith("Vendor Agreement — 3 new annotations, 7 comments, 1 new version")
        .endsWith("https://qnop.example/reviews/" + DOC);
  }

  @Test
  @DisplayName("a title that is gone still gets its line")
  void deletedDocumentStillCounts() {
    // The count is true even where the document is not resolvable any more, and
    // dropping it would understate what happened.
    String plain =
        DigestRenderer.plain(
            content(Map.of(NotificationType.COMMENT_ADDED, 1)), Map.of(), NAMES, ZONE);

    // No link either: a line that goes nowhere beats a link that 404s.
    assertThat(plain).isEqualTo("A review you take part in — 1 comment");
  }

  @Test
  @DisplayName("titles are escaped in the HTML body")
  void escapesTitles() {
    String html =
        DigestRenderer.html(
            content(Map.of(NotificationType.COMMENT_ADDED, 1)),
            Map.of(DOC, target("Terms & <Conditions>")),
            NAMES,
            ZONE);

    assertThat(html).contains("Terms &amp; &lt;Conditions&gt;").doesNotContain("<Conditions>");
  }

  @Test
  @DisplayName("each document links to its own review, not to the list")
  void everyDocumentLinksToItself() {
    // The digest's job is not to inform but to get somebody back to the right
    // review; one link to the list would make them search for what they just read.
    String html =
        DigestRenderer.html(
            content(Map.of(NotificationType.COMMENT_ADDED, 2)),
            Map.of(DOC, target("Contract")),
            NAMES,
            ZONE);

    assertThat(html).contains("href=\"https://qnop.example/reviews/" + DOC + "\"");
    assertThat(html).contains(">Contract</a>");
  }

  @Test
  @DisplayName("a document with no link is still named, just not linked")
  void unlinkedDocument() {
    String html =
        DigestRenderer.html(
            content(Map.of(NotificationType.COMMENT_ADDED, 1)),
            Map.of(DOC, new DigestRenderer.Target("Contract", null)),
            NAMES,
            ZONE);

    assertThat(html).contains(">Contract</strong>").doesNotContain("<a href");
  }

  @Test
  @DisplayName("the total is pluralised, since the subject line uses it")
  void totalPhrasePluralises() {
    assertThat(DigestRenderer.totalPhrase(1)).isEqualTo("1 update");
    assertThat(DigestRenderer.totalPhrase(11)).isEqualTo("11 updates");
  }

  private static DigestRenderer.Target target(String title) {
    return new DigestRenderer.Target(title, "https://qnop.example/reviews/" + DOC);
  }

  @Test
  @DisplayName("events are listed in order, with the time, who and what")
  void listsEventsChronologically() {
    String plain =
        DigestRenderer.plain(
            content(
                Map.of(NotificationType.ANNOTATION_CREATED, 1, NotificationType.COMMENT_ADDED, 1),
                List.of(
                    event("2026-08-03T07:12:00Z", NotificationType.ANNOTATION_CREATED, "The cap"),
                    event("2026-08-03T09:40:00Z", NotificationType.COMMENT_ADDED, null))),
            Map.of(DOC, target("Contract")),
            NAMES,
            ZONE);

    // Headline first, then the timeline — the counts say whether to read on, the
    // lines say what to expect. Times are the recipient's own clock: 07:12Z is
    // 09:12 in Berlin.
    assertThat(plain)
        .containsSubsequence(
            "Contract — 1 new annotation, 1 comment",
            "Aug 3, 09:12  Alex Reviewer raised an annotation",
            "\u201cThe cap\u201d".replace("\u201c", "\"").replace("\u201d", "\""),
            "Aug 3, 11:40  Alex Reviewer replied in a thread");
  }

  @Test
  @DisplayName("a long excerpt is shortened rather than reproduced")
  void excerptIsShortened() {
    String plain =
        DigestRenderer.plain(
            content(
                Map.of(NotificationType.COMMENT_ADDED, 1),
                List.of(
                    event(
                        "2026-08-03T09:00:00Z", NotificationType.COMMENT_ADDED, "x".repeat(400)))),
            Map.of(DOC, target("Contract")),
            NAMES,
            ZONE);

    // A digest is not the place to re-read a thread.
    assertThat(plain).contains("…").doesNotContain("x".repeat(200));
  }

  private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
  private static final Function<UUID, String> NAMES =
      actorId -> actorId == null ? "System" : "Alex Reviewer";

  private static DigestContent content(Map<NotificationType, Integer> counts) {
    return content(counts, List.of());
  }

  private static DigestContent content(
      Map<NotificationType, Integer> counts, List<DigestContent.Event> events) {
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    return new DigestContent(
        List.of(new DigestContent.DocumentSummary(DOC, counts, total, events)),
        total,
        Instant.now());
  }

  private static DigestContent.Event event(String at, NotificationType type, String excerpt) {
    return new DigestContent.Event(Instant.parse(at), type, UUID.randomUUID(), excerpt, null);
  }
}
