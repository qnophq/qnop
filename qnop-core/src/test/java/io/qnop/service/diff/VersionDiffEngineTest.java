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

import io.qnop.service.diff.VersionDiffEngine.Change;
import io.qnop.service.diff.VersionDiffEngine.ChangeType;
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
}
