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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.review.AnchorResolver.Outcome;
import io.qnop.service.review.AnchorResolver.Resolution;
import io.qnop.spi.extract.NormalizedBox;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * DB-free unit tests for the ADR-0009 re-anchoring cascade (issue #248 acceptance): exact, fuzzy,
 * and orphan paths, plus the image/geometric and determinism guarantees.
 */
class AnchorResolverTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final AnchorResolver resolver = new AnchorResolver();

  // --- exact -----------------------------------------------------------------

  @Test
  @DisplayName("a unique exact quote is PLACED with refreshed position and region")
  void exactUniqueQuoteIsPlaced() {
    String anchor = anchor("the payment terms are thirty days", "whereas ", " from invoice");
    // v2 inserts a line above, shifting the quote down — content itself unchanged.
    RenderedDocument v2 =
        doc(
            surface(
                0,
                "a freshly inserted paragraph",
                "whereas the payment terms are thirty days from invoice",
                "and nothing else changed"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.PLACED);
    JsonNode node = JSON.readTree(resolution.anchorJson());
    assertThat(node.path("textQuote").path("quote").asText())
        .isEqualTo("the payment terms are thirty days");
    int start = node.path("textPosition").path("start").asInt();
    // canonical text: line0 (28 chars) + '\n' → quote starts after "whereas " on line 1
    assertThat(start).isEqualTo("a freshly inserted paragraph".length() + 1 + "whereas ".length());
    assertThat(node.path("region").path("surfaceIndex").asInt()).isZero();
    // the region box must cover the second line's span (y = 0.05 + 1*0.05)
    assertThat(node.path("region").path("box").path("y").asDouble()).isEqualTo(0.10);
  }

  @Test
  @DisplayName("a quote that moved to a later surface is PLACED there")
  void quoteFoundOnLaterSurface() {
    String anchor = anchor("a very distinctive sentence", null, null);
    RenderedDocument v2 =
        doc(
            surface(0, "this page talks about other things"),
            surface(1, "here sits a very distinctive sentence now"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.PLACED);
    assertThat(JSON.readTree(resolution.anchorJson()).path("region").path("surfaceIndex").asInt())
        .isEqualTo(1);
  }

  // --- duplicated / fuzzy ------------------------------------------------------

  @Test
  @DisplayName("a duplicated quote whose full context still matches verbatim is uniquely PLACED")
  void duplicatedQuoteWithExactContextIsPlaced() {
    String anchor = anchor("liability is capped", "clause nine: ", " per incident");
    RenderedDocument v2 =
        doc(
            surface(
                0,
                "intro where liability is capped loosely",
                "clause nine: liability is capped per incident"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.PLACED);
    JsonNode node = JSON.readTree(resolution.anchorJson());
    int start = node.path("textPosition").path("start").asInt();
    // must pick the second occurrence (the context-matching one on line 1)
    assertThat(start)
        .isEqualTo(
            "intro where liability is capped loosely".length() + 1 + "clause nine: ".length());
  }

  @Test
  @DisplayName("a duplicated quote with only approximate context is disambiguated and MOVED")
  void duplicatedQuoteWithApproximateContextIsMoved() {
    String anchor = anchor("liability is capped", "clause nine: ", " per incident");
    // context edited ("clause 9:") so no verbatim full-context match exists — similarity decides
    RenderedDocument v2 =
        doc(
            surface(
                0,
                "first liability is capped here somehow",
                "clause 9: liability is capped per incident"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.MOVED);
    JsonNode node = JSON.readTree(resolution.anchorJson());
    int start = node.path("textPosition").path("start").asInt();
    assertThat(start)
        .isEqualTo("first liability is capped here somehow".length() + 1 + "clause 9: ".length());
  }

  @Test
  @DisplayName("a duplicated quote without any context to disambiguate is ORPHANED, never guessed")
  void duplicatedQuoteWithoutContextIsOrphaned() {
    String anchor = anchor("liability is capped", null, null);
    RenderedDocument v2 =
        doc(surface(0, "first liability is capped here", "second liability is capped there"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.ORPHANED);
    assertThat(resolution.anchorJson()).isEqualTo(anchor); // untouched
  }

  @Test
  @DisplayName("a slightly edited quote above the similarity threshold is re-placed as MOVED")
  void slightlyEditedQuoteIsMoved() {
    String anchor = anchor("the payment terms are thirty days", "whereas ", " from invoice");
    RenderedDocument v2 = doc(surface(0, "whereas the payment terms are ninety days from invoice"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.MOVED);
    assertThat(JSON.readTree(resolution.anchorJson()).path("textQuote").path("quote").asText())
        .contains("ninety days");
  }

  @Test
  @DisplayName("a heavily edited quote falls below the threshold and is ORPHANED")
  void heavilyEditedQuoteIsOrphaned() {
    String anchor = anchor("the payment terms are thirty days", "whereas ", " from invoice");
    RenderedDocument v2 =
        doc(surface(0, "the payment obligations were completely rewritten in this draft"));

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.ORPHANED);
    assertThat(resolution.anchorJson()).isEqualTo(anchor);
  }

  @Test
  @DisplayName("a removed quote (no seed hit at all) is ORPHANED")
  void removedQuoteIsOrphaned() {
    String anchor = anchor("a clause that was deleted entirely", null, null);
    RenderedDocument v2 = doc(surface(0, "totally unrelated content on this version"));

    assertThat(resolver.resolve(anchor, v2).outcome()).isEqualTo(Outcome.ORPHANED);
  }

  // --- geometric / degenerate ----------------------------------------------------

  @Test
  @DisplayName("a geometric-only anchor (image region) keeps its coordinates and is MOVED")
  void geometricAnchorKeepsCoordinates() {
    String anchor =
        "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.2,\"y\":0.3,\"width\":0.1,\"height\":0.1}}}";
    RenderedDocument v2 = doc(new Surface(0, 612, 792, List.of())); // no text layer

    Resolution resolution = resolver.resolve(anchor, v2);

    assertThat(resolution.outcome()).isEqualTo(Outcome.MOVED); // flagged "to review"
    assertThat(resolution.anchorJson()).isEqualTo(anchor); // coordinates untouched
  }

  @Test
  @DisplayName("an unreadable anchor is ORPHANED rather than guessed")
  void unreadableAnchorIsOrphaned() {
    RenderedDocument v2 = doc(surface(0, "whatever"));

    assertThat(resolver.resolve("not json at all", v2).outcome()).isEqualTo(Outcome.ORPHANED);
    assertThat(resolver.resolve(null, v2).outcome()).isEqualTo(Outcome.ORPHANED);
  }

  @Test
  @DisplayName("resolution is deterministic — same inputs, same outcome and anchor")
  void resolutionIsDeterministic() {
    String anchor = anchor("the payment terms are thirty days", "whereas ", " from invoice");
    RenderedDocument v2 =
        doc(
            surface(
                0,
                "whereas the payment terms are ninety days from invoice",
                "whereas the payment terms are sixty days from settlement"));

    Resolution first = resolver.resolve(anchor, v2);
    Resolution second = resolver.resolve(anchor, v2);

    assertThat(first).isEqualTo(second);
  }

  // --- configurable thresholds (issue #320) ----------------------------------

  @Test
  @DisplayName(
      "the similarity threshold is configurable: a lower bar re-places what the default orphans")
  void similarityThresholdIsConfigurable() {
    // The single fuzzy candidate scores exactly 0.5 (8 of 16 chars match, no context layer).
    String anchor = anchor("abcdefghijklmnop", null, null);
    RenderedDocument v2 = doc(surface(0, "abcdefgh________"));

    // Default threshold (0.75): 0.5 is below the bar → ORPHANED, never guessed.
    assertThat(new AnchorResolver().resolve(anchor, v2).outcome()).isEqualTo(Outcome.ORPHANED);

    // A deployment that lowers the bar to 0.4 accepts the same candidate → MOVED.
    ReanchoringProperties lenient = new ReanchoringProperties(0.4, null, null, null, null, null);
    assertThat(new AnchorResolver(lenient).resolve(anchor, v2).outcome()).isEqualTo(Outcome.MOVED);
  }

  // --- helpers ---------------------------------------------------------------

  /** An anchor JSON in the stored shape (#247): region + textQuote (+ position best-effort). */
  private static String anchor(String quote, String prefix, String suffix) {
    StringBuilder json = new StringBuilder("{\"region\":{\"surfaceIndex\":0,\"box\":");
    json.append("{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.05}},\"textQuote\":{");
    json.append("\"quote\":").append(quoted(quote));
    if (prefix != null) {
      json.append(",\"prefix\":").append(quoted(prefix));
    }
    if (suffix != null) {
      json.append(",\"suffix\":").append(quoted(suffix));
    }
    json.append("},\"textPosition\":{\"start\":0,\"end\":").append(quote.length()).append("}}");
    return json.toString();
  }

  private static String quoted(String value) {
    return "\"" + value.replace("\"", "\\\"") + "\"";
  }

  /** One surface whose spans are the given lines, offsets joined by the canonical '\n'. */
  private static Surface surface(int index, String... lines) {
    List<TextSpan> spans = new ArrayList<>();
    int offset = 0;
    for (int i = 0; i < lines.length; i++) {
      spans.add(
          new TextSpan(
              lines[i],
              offset,
              offset + lines[i].length(),
              new NormalizedBox(0.1, 0.05 + i * 0.05, 0.8, 0.03)));
      offset += lines[i].length() + 1;
    }
    return new Surface(index, 612, 792, spans);
  }

  private static RenderedDocument doc(Surface... surfaces) {
    return new RenderedDocument(List.of(surfaces));
  }
}
