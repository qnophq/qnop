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

import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The re-anchoring cascade of ADR-0009 as plain, DB-free, deterministic code (issue #248): given an
 * annotation's stored anchor and a new version's {@link RenderedDocument}, decide where — if
 * anywhere — the annotation sits on the new version.
 *
 * <ul>
 *   <li><b>Exact</b> — the quote (with its context) occurs unchanged exactly once → {@code PLACED}
 *       with refreshed position and region.
 *   <li><b>Fuzzy</b> — duplicated or slightly edited text; the similarity-best candidate, weighted
 *       by prefix/suffix context, wins only above a threshold and with a clear margin over the
 *       runner-up → {@code MOVED} (flagged for the reviewer).
 *   <li><b>Orphaned</b> — no unambiguous match → {@code ORPHANED}, the anchor untouched. Never
 *       silently guessed.
 * </ul>
 *
 * <p>An anchor without a text-quote layer (an image region, ADR-0009) keeps its 0..1 coordinates
 * unchanged and is flagged {@code MOVED} ("to review") — Community ships no visual re-matching.
 *
 * <p>Same inputs always produce the same resolution (job replay, ADR-0033): candidate scanning is
 * ordered, and ties break on (surface, offset).
 */
public final class AnchorResolver {

  /** How a placement resolved; maps 1:1 onto {@code PlacementStatus}. */
  public enum Outcome {
    PLACED,
    MOVED,
    ORPHANED
  }

  /** The decision plus the anchor to store — updated on PLACED/MOVED, untouched on ORPHANED. */
  public record Resolution(Outcome outcome, String anchorJson) {}

  static final double SIMILARITY_THRESHOLD = 0.75;
  static final double AMBIGUITY_MARGIN = 0.05;
  private static final int CONTEXT_LENGTH = 32;
  private static final int MAX_CANDIDATES = 64;
  private static final double QUOTE_WEIGHT = 0.7;
  private static final double CONTEXT_WEIGHT = 0.3;

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Resolves {@code anchorJson} against {@code rendered}; never throws on bad anchor content. */
  public Resolution resolve(String anchorJson, RenderedDocument rendered) {
    ParsedAnchor anchor = parse(anchorJson);
    if (anchor == null) {
      // Unreadable anchor: be honest rather than guess (ADR-0009).
      return new Resolution(Outcome.ORPHANED, anchorJson);
    }
    if (anchor.quote() == null || anchor.quote().isEmpty()) {
      // Geometric-only anchor (image region): coordinates carry over, flagged "to review".
      return new Resolution(Outcome.MOVED, anchorJson);
    }

    List<SurfaceText> surfaces = surfaceTexts(rendered);

    // 1) Exact, context-qualified: prefix+quote+suffix unchanged exactly once in the document.
    List<Match> withContext = exactMatches(surfaces, anchor, true);
    if (withContext.size() == 1) {
      return placed(anchor, withContext.get(0), surfaces);
    }
    // 2) Exact quote alone, unique in the document.
    List<Match> quoteOnly = exactMatches(surfaces, anchor, false);
    if (quoteOnly.size() == 1) {
      return placed(anchor, quoteOnly.get(0), surfaces);
    }
    // 3) Duplicated exact quote: context similarity picks one — flagged MOVED — or nobody wins.
    if (quoteOnly.size() > 1) {
      return disambiguate(anchor, quoteOnly, surfaces, anchorJson);
    }
    // 4) Quote not found verbatim: fuzzy candidates from quote-chunk seeds.
    List<Match> fuzzy = fuzzyCandidates(surfaces, anchor);
    return disambiguate(anchor, fuzzy, surfaces, anchorJson);
  }

  // --- cascade steps ----------------------------------------------------------

  private static List<Match> exactMatches(
      List<SurfaceText> surfaces, ParsedAnchor anchor, boolean withContext) {
    String prefix = anchor.prefix() == null ? "" : anchor.prefix();
    String suffix = anchor.suffix() == null ? "" : anchor.suffix();
    if (withContext && prefix.isEmpty() && suffix.isEmpty()) {
      return List.of(); // no context layer to qualify with — step degenerates to quote-only
    }
    String needle = withContext ? prefix + anchor.quote() + suffix : anchor.quote();
    int quoteShift = withContext ? prefix.length() : 0;

    List<Match> matches = new ArrayList<>();
    for (SurfaceText surface : surfaces) {
      int from = 0;
      int idx;
      while ((idx = surface.text().indexOf(needle, from)) >= 0) {
        matches.add(new Match(surface, idx + quoteShift, anchor.quote(), 1.0));
        from = idx + 1;
        if (matches.size() > MAX_CANDIDATES) {
          return matches; // pathological repetition; counts as ambiguous anyway
        }
      }
    }
    return matches;
  }

  /** The similarity-best candidate wins only above the threshold and with a clear margin. */
  private Resolution disambiguate(
      ParsedAnchor anchor, List<Match> candidates, List<SurfaceText> surfaces, String original) {
    if (candidates.isEmpty()) {
      return new Resolution(Outcome.ORPHANED, original);
    }
    List<Scored> scored = new ArrayList<>(candidates.size());
    for (Match candidate : candidates) {
      scored.add(new Scored(candidate, score(anchor, candidate, surfaces)));
    }
    scored.sort(
        Comparator.comparingDouble(Scored::score)
            .reversed()
            .thenComparingInt(s -> s.match().surface().index())
            .thenComparingInt(s -> s.match().start()));
    Scored best = scored.get(0);
    if (best.score() < SIMILARITY_THRESHOLD) {
      return new Resolution(Outcome.ORPHANED, original);
    }
    if (scored.size() > 1 && best.score() - scored.get(1).score() < AMBIGUITY_MARGIN) {
      return new Resolution(Outcome.ORPHANED, original); // ambiguous — never guessed
    }
    return new Resolution(Outcome.MOVED, rebuiltAnchor(best.match()));
  }

  /** Candidate windows seeded by verbatim occurrences of quote chunks (cheap, conservative). */
  private static List<Match> fuzzyCandidates(List<SurfaceText> surfaces, ParsedAnchor anchor) {
    String quote = anchor.quote();
    List<String> seeds = seeds(quote);
    List<Match> candidates = new ArrayList<>();
    for (SurfaceText surface : surfaces) {
      TreeSet<Integer> starts = new TreeSet<>();
      for (int s = 0; s < seeds.size(); s++) {
        String seed = seeds.get(s);
        int seedOffset = quote.indexOf(seed);
        int from = 0;
        int idx;
        while ((idx = surface.text().indexOf(seed, from)) >= 0) {
          starts.add(Math.max(0, idx - seedOffset));
          from = idx + 1;
          if (starts.size() >= MAX_CANDIDATES) {
            break;
          }
        }
      }
      Integer previous = null;
      for (int start : starts) {
        if (previous != null && start - previous <= 2) {
          continue; // collapse near-duplicate windows from different seeds
        }
        previous = start;
        int end = Math.min(surface.text().length(), start + quote.length());
        String window = surface.text().substring(start, end);
        double similarity = similarity(quote, window);
        candidates.add(new Match(surface, start, window, similarity));
      }
    }
    return candidates;
  }

  /** Up to four seeds of the quote; short quotes seed as a whole. */
  private static List<String> seeds(String quote) {
    int chunk = Math.max(8, quote.length() / 4);
    if (quote.length() <= chunk) {
      return List.of(quote);
    }
    List<String> seeds = new ArrayList<>(4);
    for (int at = 0; at + chunk <= quote.length() && seeds.size() < 4; at += chunk) {
      seeds.add(quote.substring(at, at + chunk));
    }
    return seeds;
  }

  // --- scoring ----------------------------------------------------------------

  private static double score(ParsedAnchor anchor, Match match, List<SurfaceText> surfaces) {
    String text = match.surface().text();
    double quoteSimilarity = match.quoteSimilarity();

    String prefix = anchor.prefix() == null ? "" : anchor.prefix();
    String suffix = anchor.suffix() == null ? "" : anchor.suffix();
    if (prefix.isEmpty() && suffix.isEmpty()) {
      return quoteSimilarity; // no context layer — the quote is all we have
    }
    double contextSimilarity = 0;
    int layers = 0;
    if (!prefix.isEmpty()) {
      int from = Math.max(0, match.start() - prefix.length());
      contextSimilarity += similarity(prefix, text.substring(from, match.start()));
      layers++;
    }
    if (!suffix.isEmpty()) {
      int end = match.start() + match.window().length();
      int to = Math.min(text.length(), end + suffix.length());
      contextSimilarity += similarity(suffix, text.substring(end, to));
      layers++;
    }
    return QUOTE_WEIGHT * quoteSimilarity + CONTEXT_WEIGHT * (contextSimilarity / layers);
  }

  /** Normalized Levenshtein similarity in 0..1; both inputs are window-sized (bounded cost). */
  static double similarity(String a, String b) {
    if (a.equals(b)) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    int distance = previous[b.length()];
    return 1.0 - (double) distance / Math.max(a.length(), b.length());
  }

  // --- anchor (re)construction --------------------------------------------------

  private Resolution placed(ParsedAnchor anchor, Match match, List<SurfaceText> surfaces) {
    return new Resolution(Outcome.PLACED, rebuiltAnchor(match));
  }

  /**
   * Builds the resolved anchor: fresh position offsets, the matched text as the new quote with
   * regenerated context slices, and a region box unioned from the spans the match covers.
   */
  private static String rebuiltAnchor(Match match) {
    SurfaceText surface = match.surface();
    int start = match.start();
    int end = start + match.window().length();
    String text = surface.text();

    Map<String, Object> anchor = new LinkedHashMap<>();
    anchor.put(
        "region", Map.of("surfaceIndex", surface.index(), "box", boxFor(surface, start, end)));
    Map<String, Object> quote = new LinkedHashMap<>();
    quote.put("quote", match.window());
    quote.put("prefix", text.substring(Math.max(0, start - CONTEXT_LENGTH), start));
    quote.put("suffix", text.substring(end, Math.min(text.length(), end + CONTEXT_LENGTH)));
    anchor.put("textQuote", quote);
    anchor.put("textPosition", Map.of("start", start, "end", end));
    return MAPPER.writeValueAsString(anchor);
  }

  /** Union of the boxes of all spans overlapping [start, end) — the match's visual footprint. */
  private static Map<String, Double> boxFor(SurfaceText surface, int start, int end) {
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    boolean any = false;
    for (TextSpan span : surface.surface().textSpans()) {
      if (span.startOffset() < end && span.endOffset() > start) {
        any = true;
        minX = Math.min(minX, span.box().x());
        minY = Math.min(minY, span.box().y());
        maxX = Math.max(maxX, span.box().x() + span.box().width());
        maxY = Math.max(maxY, span.box().y() + span.box().height());
      }
    }
    if (!any) {
      // Text matched but no span covers it — impossible for extractor-produced text; degrade to
      // a zero-size box at the surface origin rather than invent geometry.
      return Map.of("x", 0.0, "y", 0.0, "width", 0.0, "height", 0.0);
    }
    return Map.of("x", minX, "y", minY, "width", maxX - minX, "height", maxY - minY);
  }

  // --- input models -------------------------------------------------------------

  /**
   * Canonical text per surface: span texts joined by a single {@code \n} (extractor convention).
   */
  private static List<SurfaceText> surfaceTexts(RenderedDocument rendered) {
    List<SurfaceText> texts = new ArrayList<>(rendered.surfaces().size());
    for (Surface surface : rendered.surfaces()) {
      StringBuilder joined = new StringBuilder();
      for (TextSpan span : surface.textSpans()) {
        if (!joined.isEmpty()) {
          joined.append('\n');
        }
        joined.append(span.text());
      }
      texts.add(new SurfaceText(surface.index(), surface, joined.toString()));
    }
    return texts;
  }

  /** Lenient parse of the stored anchor JSON; null when unreadable. */
  private static ParsedAnchor parse(String anchorJson) {
    if (anchorJson == null || anchorJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = MAPPER.readTree(anchorJson);
      JsonNode quote = root.path("textQuote");
      return new ParsedAnchor(
          textOrNull(quote.path("quote")),
          textOrNull(quote.path("prefix")),
          textOrNull(quote.path("suffix")));
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String textOrNull(JsonNode node) {
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }

  private record ParsedAnchor(String quote, String prefix, String suffix) {}

  private record SurfaceText(int index, Surface surface, String text) {}

  /** A candidate location: where, what text sits there, and how similar it is to the quote. */
  private record Match(SurfaceText surface, int start, String window, double quoteSimilarity) {}

  private record Scored(Match match, double score) {}
}
