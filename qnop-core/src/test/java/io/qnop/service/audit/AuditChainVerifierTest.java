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
package io.qnop.service.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.repository.AuditChainHead;
import io.qnop.repository.AuditChainPrunedRange;
import io.qnop.repository.AuditChainRow;
import io.qnop.service.audit.AuditChainVerifier.Problem;
import io.qnop.service.audit.AuditChainVerifier.ProblemKind;
import io.qnop.service.audit.AuditChainVerifier.Report;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditChainVerifierTest {

  /** Builds a correctly sealed chain of {@code n} rows (seq 1..n). */
  private static List<AuditChainRow> chain(int n) {
    List<AuditChainRow> rows = new ArrayList<>();
    String prev = AuditChainHasher.GENESIS;
    for (int seq = 1; seq <= n; seq++) {
      AuditChainRow row = sealed(seq, prev, "event." + seq, "{\"n\": " + seq + "}");
      rows.add(row);
      prev = row.rowHash();
    }
    return rows;
  }

  private static AuditChainRow sealed(long seq, String prev, String eventType, String detail) {
    UUID id = new UUID(0, seq);
    String hash =
        AuditChainHasher.linkHash(
            prev,
            AuditChainHasher.canonical(id, "SYSTEM", null, eventType, null, detail, seq * 1000));
    return new AuditChainRow(
        seq, prev, hash, id, "SYSTEM", null, eventType, null, detail, seq * 1000);
  }

  private static Report verify(
      List<AuditChainRow> rows, Collection<AuditChainPrunedRange> ranges, AuditChainHead head) {
    AuditChainVerifier verifier = new AuditChainVerifier(ranges);
    rows.forEach(verifier::accept);
    return verifier.finish(head, 0);
  }

  private static AuditChainHead headOf(List<AuditChainRow> rows) {
    AuditChainRow last = rows.getLast();
    return new AuditChainHead(last.seq(), last.rowHash());
  }

  /** A tombstone range covering rows[from-1 .. to-1], recorded at {@code recordedSeq}. */
  private static AuditChainPrunedRange range(
      List<AuditChainRow> all, int from, int to, long recordedSeq) {
    return new AuditChainPrunedRange(
        recordedSeq, from, to, all.get(from - 1).prevHash(), all.get(to - 1).rowHash());
  }

  private static List<AuditChainRow> without(List<AuditChainRow> rows, int... seqs) {
    List<Long> drop = IntStream.of(seqs).mapToObj(Long::valueOf).toList();
    return rows.stream().filter(r -> !drop.contains(r.seq())).toList();
  }

  @Test
  @DisplayName("an untouched chain verifies")
  void intactChain() {
    List<AuditChainRow> rows = chain(5);

    Report report = verify(rows, List.of(), headOf(rows));

    assertThat(report.intact()).isTrue();
    assertThat(report.rowsChecked()).isEqualTo(5);
    assertThat(report.problems()).isEmpty();
  }

  @Test
  @DisplayName("an empty chain at the genesis head verifies")
  void emptyChain() {
    Report report = verify(List.of(), List.of(), new AuditChainHead(0, AuditChainHasher.GENESIS));

    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("altered content is reported once, at the altered row")
  void alteredContent() {
    List<AuditChainRow> rows = new ArrayList<>(chain(5));
    AuditChainRow original = rows.get(2);
    rows.set(
        2,
        new AuditChainRow(
            original.seq(),
            original.prevHash(),
            original.rowHash(),
            original.id(),
            original.scope(),
            original.documentId(),
            original.eventType(),
            original.actorId(),
            "{\"n\": 999}",
            original.createdAtMicros()));

    Report report = verify(rows, List.of(), headOf(rows));

    assertThat(report.intact()).isFalse();
    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.CONTENT_MISMATCH, 3, 3));
  }

  @Test
  @DisplayName("a row whose hash was recomputed after editing breaks the next link")
  void rehashedRowBreaksNextLink() {
    List<AuditChainRow> rows = new ArrayList<>(chain(4));
    rows.set(1, sealed(2, rows.get(0).rowHash(), "event.2", "{\"n\": 999}"));

    Report report = verify(rows, List.of(), headOf(chain(4)));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.LINK_MISMATCH, 3, 3));
  }

  @Test
  @DisplayName("rows missing without a tombstone are an unsanctioned gap")
  void unsanctionedGap() {
    List<AuditChainRow> all = chain(6);

    Report report = verify(without(all, 3, 4), List.of(), headOf(all));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.UNSANCTIONED_GAP, 3, 4));
  }

  @Test
  @DisplayName("rows removed by a recorded prune are bridged")
  void sanctionedPrune() {
    List<AuditChainRow> all = new ArrayList<>(chain(6));
    // The tombstone itself is sealed after the pruned rows (here: as seq 7).
    AuditChainRow tombstone = sealed(7, all.get(5).rowHash(), "audit.chain.pruned", "{}");
    all.add(tombstone);

    Report report =
        verify(
            without(all, 2, 4, 5), List.of(range(all, 2, 2, 7), range(all, 4, 5, 7)), headOf(all));

    assertThat(report.intact()).isTrue();
    assertThat(report.rowsChecked()).isEqualTo(4);
  }

  @Test
  @DisplayName("a prune of the newest rows is bridged right before its own tombstone")
  void sanctionedPruneOfNewestRows() {
    List<AuditChainRow> all = new ArrayList<>(chain(5));
    all.add(sealed(6, all.get(4).rowHash(), "audit.chain.pruned", "{}"));

    Report report = verify(without(all, 4, 5), List.of(range(all, 4, 5, 6)), headOf(all));

    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("a prune bridged up to the head still has to end on the head hash")
  void pruneBridgesToHead() {
    List<AuditChainRow> all = chain(5);
    AuditChainPrunedRange tail = range(all, 4, 5, 6);

    Report report =
        verify(without(all, 4, 5), List.of(tail), new AuditChainHead(5, "e".repeat(64)));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.HEAD_MISMATCH, 5, 5));
  }

  @Test
  @DisplayName("a tombstone recorded before the rows it claims is ignored")
  void tombstoneCannotPredateItsRange() {
    List<AuditChainRow> all = chain(6);

    Report report = verify(without(all, 4), List.of(range(all, 4, 4, 2)), headOf(all));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.UNSANCTIONED_GAP, 4, 4));
  }

  @Test
  @DisplayName("a tombstone whose boundary hash does not link is reported")
  void badPruneRange() {
    List<AuditChainRow> all = chain(6);
    AuditChainPrunedRange forged =
        new AuditChainPrunedRange(
            9L, 3, 3, "f".repeat(64), all.get(2).rowHash()); // wrong lead-in hash

    Report report = verify(without(all, 3), List.of(forged), headOf(all));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_PRUNE_RANGE, 3, 3));
  }

  @Test
  @DisplayName("rows cut from the end without a tombstone do not reach the head")
  void truncatedTail() {
    List<AuditChainRow> all = chain(5);

    Report report = verify(all.subList(0, 3), List.of(), headOf(all));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.UNSANCTIONED_GAP, 4, 5));
  }

  @Test
  @DisplayName("a rewound head is reported")
  void rewoundHead() {
    List<AuditChainRow> all = chain(5);

    Report report = verify(all, List.of(), new AuditChainHead(3, all.get(2).rowHash()));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.HEAD_MISMATCH, 3, 5));
  }

  @Test
  @DisplayName("unsealed committed rows are reported")
  void unsealedRows() {
    List<AuditChainRow> all = chain(2);
    AuditChainVerifier verifier = new AuditChainVerifier(List.of());
    all.forEach(verifier::accept);

    Report report = verifier.finish(headOf(all), 3);

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.UNSEALED_ROWS, 3, 3));
  }

  @Test
  @DisplayName("the report keeps a bounded number of problems but counts them all")
  void problemListIsBounded() {
    List<AuditChainRow> rows =
        chain(60).stream()
            .map(
                r ->
                    new AuditChainRow(
                        r.seq(),
                        r.prevHash(),
                        r.rowHash(),
                        r.id(),
                        r.scope(),
                        r.documentId(),
                        "tampered",
                        r.actorId(),
                        r.detail(),
                        r.createdAtMicros()))
            .toList();

    Report report = verify(rows, List.of(), headOf(rows));

    assertThat(report.problemCount()).isEqualTo(60);
    assertThat(report.problems()).hasSize(AuditChainVerifier.MAX_REPORTED_PROBLEMS);
  }
}
