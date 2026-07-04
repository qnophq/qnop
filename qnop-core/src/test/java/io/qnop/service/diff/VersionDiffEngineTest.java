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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.qnop.service.diff.VersionDiffEngine.Change;
import io.qnop.service.diff.VersionDiffEngine.ChangeType;
import io.qnop.service.diff.VersionDiffEngine.Location;
import io.qnop.spi.extract.NormalizedBox;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DB-free diff core (issue #249, ADR-0034): word-granularity change detection over the
 * document-wide canonical text, and the mapping of changed spans back to the boxes of the text runs
 * they cover.
 */
class VersionDiffEngineTest {

  /** Builds one surface whose spans are the given lines, with offsets per the ADR-0032 contract. */
  private static Surface surface(int index, double y0, String... lines) {
    List<TextSpan> spans = new ArrayList<>();
    int offset = 0;
    double y = y0;
    for (String line : lines) {
      spans.add(
          new TextSpan(line, offset, offset + line.length(), new NormalizedBox(0.1, y, 0.8, 0.05)));
      offset += line.length() + 1; // the '\n' joiner
      y += 0.1;
    }
    return new Surface(index, 612, 792, spans);
  }

  private static RenderedDocument doc(Surface... surfaces) {
    return new RenderedDocument(List.of(surfaces));
  }

  @Test
  @DisplayName("identical documents produce no changes")
  void identicalDocumentsProduceNoChanges() {
    RenderedDocument a = doc(surface(0, 0.1, "the quick brown fox", "jumps over the dog"));
    RenderedDocument b = doc(surface(0, 0.1, "the quick brown fox", "jumps over the dog"));

    assertThat(VersionDiffEngine.diff(a, b)).isEmpty();
  }

  @Test
  @DisplayName("a replaced word is a CHANGED change carrying both texts and both locations")
  void replacedWordIsChanged() {
    RenderedDocument from = doc(surface(0, 0.1, "the quick brown fox"));
    RenderedDocument to = doc(surface(0, 0.1, "the quick red fox"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    Change change = changes.get(0);
    assertThat(change.type()).isEqualTo(ChangeType.CHANGED);
    assertThat(change.fromText()).isEqualTo("brown");
    assertThat(change.toText()).isEqualTo("red");
    assertThat(change.fromLocations()).hasSize(1);
    assertThat(change.fromLocations().get(0).surfaceIndex()).isZero();
    assertThat(change.toLocations()).hasSize(1);
  }

  @Test
  @DisplayName("appended text is INSERTED, located only in the to-version")
  void appendedTextIsInserted() {
    RenderedDocument from = doc(surface(0, 0.1, "clause one"));
    RenderedDocument to = doc(surface(0, 0.1, "clause one", "clause two added"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    Change change = changes.get(0);
    assertThat(change.type()).isEqualTo(ChangeType.INSERTED);
    assertThat(change.fromText()).isEmpty();
    assertThat(change.fromLocations()).isEmpty();
    assertThat(change.toText()).isEqualTo("clause two added");
    assertThat(change.toLocations()).hasSize(1);
    assertThat(change.toLocations().get(0).box().y()).isEqualTo(0.2);
  }

  @Test
  @DisplayName("removed text is DELETED, located only in the from-version")
  void removedTextIsDeleted() {
    RenderedDocument from = doc(surface(0, 0.1, "keep this", "drop that entirely"));
    RenderedDocument to = doc(surface(0, 0.1, "keep this"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    Change change = changes.get(0);
    assertThat(change.type()).isEqualTo(ChangeType.DELETED);
    assertThat(change.fromText()).isEqualTo("drop that entirely");
    assertThat(change.fromLocations()).hasSize(1);
    assertThat(change.toText()).isEmpty();
    assertThat(change.toLocations()).isEmpty();
  }

  @Test
  @DisplayName("a change spanning two text runs yields one location per run")
  void changeSpanningTwoRunsYieldsBothBoxes() {
    RenderedDocument from = doc(surface(0, 0.1, "alpha beta", "gamma delta"));
    RenderedDocument to = doc(surface(0, 0.1, "alpha X", "Y delta"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    // "beta gamma" → "X Y": one CHANGED covering the end of run 1 and the start of run 2.
    assertThat(changes).hasSize(1);
    Change change = changes.get(0);
    assertThat(change.type()).isEqualTo(ChangeType.CHANGED);
    assertThat(change.fromLocations()).hasSize(2);
    assertThat(change.fromLocations().get(0).box().y()).isEqualTo(0.1);
    assertThat(change.fromLocations().get(1).box().y()).isEqualTo(0.2);
  }

  @Test
  @DisplayName("content moving across a surface boundary matches (document-wide diff)")
  void reflowAcrossSurfacesIsNotAChange() {
    // The same words, but the second line reflowed onto the next page.
    RenderedDocument from = doc(surface(0, 0.1, "shared intro text", "tail sentence"));
    RenderedDocument to =
        doc(surface(0, 0.1, "shared intro text"), surface(1, 0.1, "tail sentence"));

    assertThat(VersionDiffEngine.diff(from, to)).isEmpty();
  }

  @Test
  @DisplayName("an insertion on a later surface is attributed to that surface")
  void insertionOnLaterSurfaceCarriesItsIndex() {
    RenderedDocument from = doc(surface(0, 0.1, "page one"), surface(1, 0.1, "page two"));
    RenderedDocument to =
        doc(surface(0, 0.1, "page one"), surface(1, 0.1, "page two", "with brand new content"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).type()).isEqualTo(ChangeType.INSERTED);
    assertThat(changes.get(0).toLocations()).hasSize(1);
    assertThat(changes.get(0).toLocations().get(0).surfaceIndex()).isEqualTo(1);
  }

  @Test
  @DisplayName("image-only documents (no text layer) yield an empty change list")
  void imageOnlyDocumentsYieldNoChanges() {
    RenderedDocument a = doc(new Surface(0, 800, 600, List.of()));
    RenderedDocument b = doc(new Surface(0, 1024, 768, List.of()));

    assertThat(VersionDiffEngine.diff(a, b)).isEmpty();
  }

  // --- word-accurate geometry (issue #369): boxes are cut to the changed characters ---------

  @Test
  @DisplayName("a mid-run word is cut at uniform character boundaries without charAdvances")
  void midRunWordIsCutAtUniformCharBoundaries() {
    // "brown" covers offsets [10,15) of the 19-char run; box x=0.1 width=0.8.
    RenderedDocument from = doc(surface(0, 0.1, "the quick brown fox"));
    RenderedDocument to = doc(surface(0, 0.1, "the quick red fox"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    Location location = changes.get(0).fromLocations().get(0);
    double charWidth = 0.8 / 19;
    assertThat(location.box().x()).isCloseTo(0.1 + 10 * charWidth, within(1e-9));
    assertThat(location.box().width()).isCloseTo(5 * charWidth, within(1e-9));
    assertThat(location.box().y()).isEqualTo(0.1);
    assertThat(location.box().height()).isEqualTo(0.05);
  }

  @Test
  @DisplayName("a mid-run word is cut at the true glyph edges when the run carries charAdvances")
  void midRunWordIsCutAtGlyphEdgesWithCharAdvances() {
    // "cd" covers offsets [3,5): left edge = advances[2], right edge = advances[4].
    NormalizedBox box = new NormalizedBox(0.1, 0.1, 0.6, 0.05);
    List<Double> advances = List.of(0.2, 0.3, 0.35, 0.5, 0.7);
    RenderedDocument from =
        doc(new Surface(0, 612, 792, List.of(new TextSpan("ab cd", 0, 5, box, advances))));
    RenderedDocument to = doc(surface(0, 0.1, "ab XX"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    Location location = changes.get(0).fromLocations().get(0);
    assertThat(location.box().x()).isCloseTo(0.35, within(1e-9));
    assertThat(location.box().width()).isCloseTo(0.7 - 0.35, within(1e-9));
  }

  @Test
  @DisplayName(
      "a chunk across two runs cuts the first run from the word start and the last to the word end")
  void chunkAcrossRunsCutsFirstAndLastRun() {
    // "beta gamma" → "X Y": run 1 is cut from "beta" (offset 6 of 10), run 2 up to "gamma" (5 of
    // 11).
    RenderedDocument from = doc(surface(0, 0.1, "alpha beta", "gamma delta"));
    RenderedDocument to = doc(surface(0, 0.1, "alpha X", "Y delta"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    List<Location> locations = changes.get(0).fromLocations();
    assertThat(locations).hasSize(2);
    assertThat(locations.get(0).box().x()).isCloseTo(0.1 + 6 * (0.8 / 10), within(1e-9));
    assertThat(locations.get(0).box().width()).isCloseTo(4 * (0.8 / 10), within(1e-9));
    assertThat(locations.get(1).box().x()).isCloseTo(0.1, within(1e-9));
    assertThat(locations.get(1).box().width()).isCloseTo(5 * (0.8 / 11), within(1e-9));
  }

  @Test
  @DisplayName("a chunk covering a whole run keeps that run's original box")
  void wholeRunChunkKeepsTheRunBox() {
    RenderedDocument from = doc(surface(0, 0.1, "clause one"));
    RenderedDocument to = doc(surface(0, 0.1, "clause one", "clause two added"));

    List<Change> changes = VersionDiffEngine.diff(from, to);

    assertThat(changes).hasSize(1);
    NormalizedBox box = changes.get(0).toLocations().get(0).box();
    assertThat(box.x()).isEqualTo(0.1);
    assertThat(box.width()).isEqualTo(0.8);
  }
}
