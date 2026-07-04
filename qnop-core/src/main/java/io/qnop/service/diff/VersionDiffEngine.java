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
package io.qnop.service.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.qnop.spi.extract.NormalizedBox;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import java.util.ArrayList;
import java.util.List;

/**
 * The inter-version diff core (issue #249, ADR-0034): a Myers diff at word granularity over the two
 * versions' extracted text layers, with every changed span mapped back to the normalized boxes of
 * the affected text — cut to the changed characters within each run (issue #369) — so the frontend
 * can highlight the change on the rendered original.
 *
 * <p>The diff runs over the <em>document-wide</em> canonical text (each surface's canonical text —
 * spans joined by {@code \n}, ADR-0032 — concatenated with {@code \n} between surfaces), so content
 * reflowing across a page boundary is matched, not reported as delete + insert. Deliberately plain,
 * DB-free logic (ADR-0004 guardrail): the service around it owns authz, gates, and the cache.
 *
 * <p>Documents without a text layer (images) yield an empty change list — the honest Community
 * answer per ADR-0034 (the frontend falls back to side-by-side).
 */
public final class VersionDiffEngine {

  private VersionDiffEngine() {}

  /** The kind of a change, from the perspective of the {@code from → to} direction. */
  public enum ChangeType {
    INSERTED,
    DELETED,
    CHANGED
  }

  /** Where a changed span sits on a version: one surface and one normalized box of changed text. */
  public record Location(int surfaceIndex, NormalizedBox box) {}

  /**
   * One contiguous change. {@code fromText}/{@code fromLocations} describe the affected content of
   * the from-version (empty for {@link ChangeType#INSERTED}); {@code toText}/{@code toLocations}
   * the to-version's (empty for {@link ChangeType#DELETED}).
   */
  public record Change(
      ChangeType type,
      String fromText,
      List<Location> fromLocations,
      String toText,
      List<Location> toLocations) {}

  /** Diffs two rendered documents; the result is stable for the (immutable) input pair. */
  public static List<Change> diff(RenderedDocument from, RenderedDocument to) {
    TokenizedDocument left = TokenizedDocument.of(from);
    TokenizedDocument right = TokenizedDocument.of(to);

    List<Change> changes = new ArrayList<>();
    for (AbstractDelta<String> delta : DiffUtils.diff(left.words(), right.words()).getDeltas()) {
      switch (delta.getType()) {
        case DELETE ->
            changes.add(
                new Change(
                    ChangeType.DELETED,
                    left.textOf(delta.getSource()),
                    left.locate(delta.getSource()),
                    "",
                    List.of()));
        case INSERT ->
            changes.add(
                new Change(
                    ChangeType.INSERTED,
                    "",
                    List.of(),
                    right.textOf(delta.getTarget()),
                    right.locate(delta.getTarget())));
        case CHANGE ->
            changes.add(
                new Change(
                    ChangeType.CHANGED,
                    left.textOf(delta.getSource()),
                    left.locate(delta.getSource()),
                    right.textOf(delta.getTarget()),
                    right.locate(delta.getTarget())));
        case EQUAL -> {
          // not emitted by DiffUtils.diff, present only for exhaustiveness
        }
      }
    }
    return List.copyOf(changes);
  }

  /** A word token: its text and its inclusive start offset in the document-wide canonical text. */
  private record Word(String text, int start) {}

  /** A span's home: its surface index and its offset range in the document-wide canonical text. */
  private record LocatedSpan(
      int surfaceIndex, int start, int end, NormalizedBox box, List<Double> charAdvances) {

    /**
     * The run's box cut to its overlap with a chunk range — glyph-true via {@code charAdvances}
     * (#290) when present, otherwise the uniform character-count approximation. The same mapping
     * the viewer's {@code boxesForRange} applies client-side, so a diff highlight and a text
     * selection land on identical geometry.
     */
    NormalizedBox cut(Range range) {
      int length = end - start;
      int from = Math.max(range.start(), start) - start;
      int to = Math.min(range.end(), end) - start;
      if (from <= 0 && to >= length) {
        return box;
      }
      double left = clamp(leftEdgeOf(from));
      double right = clamp(rightEdgeOf(to - 1));
      return new NormalizedBox(left, box.y(), Math.max(0, right - left), box.height());
    }

    private double leftEdgeOf(int index) {
      return index <= 0 ? box.x() : rightEdgeOf(index - 1);
    }

    private double rightEdgeOf(int index) {
      if (charAdvances != null) {
        return charAdvances.get(Math.min(index, charAdvances.size() - 1));
      }
      return box.x() + box.width() * (index + 1) / (end - start);
    }

    private static double clamp(double value) {
      return Math.clamp(value, 0.0d, 1.0d);
    }
  }

  /**
   * One side of the comparison, indexed for the diff: the word tokens of the document-wide
   * canonical text plus the global offset range of every text run (for the box mapping).
   */
  private record TokenizedDocument(
      String canonicalText, List<Word> tokens, List<LocatedSpan> spans) {

    static TokenizedDocument of(RenderedDocument document) {
      StringBuilder canonical = new StringBuilder();
      List<LocatedSpan> spans = new ArrayList<>();
      for (Surface surface : document.surfaces()) {
        if (!canonical.isEmpty()) {
          canonical.append('\n');
        }
        int surfaceBase = canonical.length();
        // Per ADR-0032 a surface's canonical text is its spans joined by a single '\n', and each
        // span's offsets are relative to that text — so the global range is base + span offsets.
        for (TextSpan span : surface.textSpans()) {
          spans.add(
              new LocatedSpan(
                  surface.index(),
                  surfaceBase + span.startOffset(),
                  surfaceBase + span.endOffset(),
                  span.box(),
                  span.charAdvances()));
        }
        canonical.append(canonicalTextOf(surface));
      }
      return new TokenizedDocument(canonical.toString(), tokenize(canonical), List.copyOf(spans));
    }

    private static String canonicalTextOf(Surface surface) {
      StringBuilder text = new StringBuilder();
      for (TextSpan span : surface.textSpans()) {
        if (!text.isEmpty()) {
          text.append('\n');
        }
        text.append(span.text());
      }
      return text.toString();
    }

    private static List<Word> tokenize(CharSequence text) {
      List<Word> words = new ArrayList<>();
      int i = 0;
      while (i < text.length()) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
          i++;
        }
        int start = i;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
          i++;
        }
        if (i > start) {
          words.add(new Word(text.subSequence(start, i).toString(), start));
        }
      }
      return List.copyOf(words);
    }

    /** The word texts, the unit the Myers diff compares. */
    List<String> words() {
      return tokens.stream().map(Word::text).toList();
    }

    /** The verbatim canonical text covered by a delta chunk (first to last affected word). */
    String textOf(Chunk<String> chunk) {
      Range range = rangeOf(chunk);
      return canonicalText.substring(range.start(), range.end());
    }

    /** The boxes of the affected text, one per overlapping run, cut to the chunk (issue #369). */
    List<Location> locate(Chunk<String> chunk) {
      Range range = rangeOf(chunk);
      List<Location> locations = new ArrayList<>();
      for (LocatedSpan span : spans) {
        if (span.start() < range.end() && range.start() < span.end()) {
          locations.add(new Location(span.surfaceIndex(), span.cut(range)));
        }
      }
      return List.copyOf(locations);
    }

    private Range rangeOf(Chunk<String> chunk) {
      Word first = tokens.get(chunk.getPosition());
      Word last = tokens.get(chunk.getPosition() + chunk.getLines().size() - 1);
      return new Range(first.start(), last.start() + last.text().length());
    }
  }

  private record Range(int start, int end) {}
}
