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
import io.qnop.repository.AuditChainTombstoneRow;
import io.qnop.service.audit.AuditChainVerifier.Problem;
import io.qnop.service.audit.AuditChainVerifier.ProblemKind;
import io.qnop.service.audit.AuditChainVerifier.Report;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
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
    AuditChainVerifier verifier = new AuditChainVerifier(tombstones(ranges), Set.of(DOC));
    rows.forEach(verifier::accept);
    return verifier.finish(head, 0, rows.size());
  }

  private static final String DOC = "0190a1b2-c3d4-7e5f-8a9b-000000000001";

  /** Wraps ranges into one well-formed tombstone per recording position, naming {@link #DOC}. */
  private static List<AuditChainTombstone> tombstones(Collection<AuditChainPrunedRange> ranges) {
    return ranges.stream()
        .collect(
            Collectors.groupingBy(
                AuditChainPrunedRange::recordedSeq, TreeMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .map(e -> new AuditChainTombstone(e.getKey(), e.getValue(), List.of(DOC), false))
        .toList();
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
    AuditChainVerifier verifier = new AuditChainVerifier(List.of(), Set.of());
    all.forEach(verifier::accept);

    Report report = verifier.finish(headOf(all), 3, 5);

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.UNSEALED_ROWS, 3, 3));
  }

  @Test
  @DisplayName("rows the walk did not see are reported against the table's row count")
  void rowCountMismatch() {
    List<AuditChainRow> all = chain(2);
    AuditChainVerifier verifier = new AuditChainVerifier(List.of(), Set.of());
    all.forEach(verifier::accept);

    Report report = verifier.finish(headOf(all), 0, 3);

    assertThat(report.problems())
        .containsExactly(new Problem(ProblemKind.ROW_COUNT_MISMATCH, 2, 3));
  }

  @Test
  @DisplayName("a malformed tombstone is reported and bridges nothing")
  void malformedTombstone() {
    List<AuditChainRow> all = chain(4);
    AuditChainTombstone broken =
        AuditChainTombstone.parse(new AuditChainTombstoneRow(9L, "{\"ranges\": \"oops\"}"));
    AuditChainVerifier verifier = new AuditChainVerifier(List.of(broken), Set.of());
    without(all, 2).forEach(verifier::accept);

    Report report = verifier.finish(headOf(all), 0, 3);

    assertThat(report.problems())
        .containsExactly(
            new Problem(ProblemKind.BAD_PRUNE_RANGE, 9, 9),
            new Problem(ProblemKind.UNSANCTIONED_GAP, 2, 2));
  }

  @Test
  @DisplayName("a prune of a document whose deletion nothing explains is reported")
  void unexplainedPrune() {
    List<AuditChainRow> all = new ArrayList<>(chain(3));
    all.add(sealed(4, all.get(2).rowHash(), "audit.chain.pruned", "{}"));
    AuditChainTombstone tombstone =
        new AuditChainTombstone(4L, List.of(range(all, 2, 2, 4)), List.of("other-doc"), false);
    AuditChainVerifier verifier = new AuditChainVerifier(List.of(tombstone), Set.of(DOC));
    without(all, 2).forEach(verifier::accept);

    Report report = verifier.finish(headOf(all), 0, 3);

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.UNEXPLAINED_PRUNE, 4, 4));
  }

  @Test
  @DisplayName("a failed run never reads as intact")
  void failedReportIsNotIntact() {
    assertThat(Report.failed().headSeq()).isEqualTo(-1);
    failedReportShape();
  }

  @Test
  @DisplayName("a second tombstone claiming the same positions is reported; the first is kept")
  void duplicateRangeReported() {
    List<AuditChainRow> all = new ArrayList<>(chain(3));
    all.add(sealed(4, all.get(2).rowHash(), "audit.chain.pruned", "{}"));
    all.add(sealed(5, all.get(3).rowHash(), "audit.chain.pruned", "{}"));
    AuditChainPrunedRange genuine = range(all, 2, 2, 4);
    AuditChainPrunedRange rival = new AuditChainPrunedRange(5L, 2, 2, "a", "b");

    Report report = verify(without(all, 2), List.of(genuine, rival), headOf(all));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_PRUNE_RANGE, 2, 2));
  }

  private static void failedReportShape() {
    assertThat(Report.failed().intact()).isFalse();
    assertThat(Report.failed().problems())
        .extracting(Problem::kind)
        .containsExactly(ProblemKind.VERIFICATION_ERROR);
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

  // --- redactions (issue #952) ---------------------------------------------------------------

  private static final UUID REDACTION = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0000000000aa");

  /** The row with pseudonymised content, chain columns untouched, marked with the redaction. */
  private static AuditChainRow redacted(AuditChainRow row, UUID redactionId) {
    return new AuditChainRow(
        row.seq(),
        row.prevHash(),
        row.rowHash(),
        row.id(),
        row.scope(),
        row.documentId(),
        row.eventType(),
        null,
        "{\"userId\": \"erased-user:x\"}",
        row.createdAtMicros(),
        redactionId);
  }

  private static Report verifyRedacted(
      List<AuditChainRow> rows, List<AuditChainRedaction> redactions) {
    AuditChainVerifier verifier = new AuditChainVerifier(List.of(), Set.of(), redactions);
    rows.forEach(verifier::accept);
    return verifier.finish(headOf(rows), 0, rows.size());
  }

  /** A chain of 3 rows plus the redaction event at seq 4 listing rows 2 with the given hash. */
  private static List<AuditChainRow> chainWithRedactionEvent() {
    List<AuditChainRow> rows = new ArrayList<>(chain(3));
    rows.add(sealed(4, rows.get(2).rowHash(), "audit.chain.redacted", "{}"));
    return rows;
  }

  @Test
  @DisplayName("a redacted row listed with its exact hash by a later redaction event passes")
  void redactedRowWithMatchingEventPasses() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRedaction event =
        new AuditChainRedaction(4L, REDACTION, Map.of(2L, rows.get(1).rowHash()), 0, false);
    rows.set(1, redacted(rows.get(1), REDACTION));

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).isEmpty();
    assertThat(report.intact()).isTrue();
  }

  @Test
  @DisplayName("a redacted row without a redaction event is reported")
  void redactedRowWithoutEventFails() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    rows.set(1, redacted(rows.get(1), REDACTION));

    Report report = verifyRedacted(rows, List.of());

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("a content change without the redaction marker stays a content mismatch")
  void unmarkedChangeIsContentMismatch() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRedaction event =
        new AuditChainRedaction(4L, REDACTION, Map.of(2L, rows.get(1).rowHash()), 0, false);
    rows.set(1, redacted(rows.get(1), null));

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.CONTENT_MISMATCH, 2, 2));
  }

  @Test
  @DisplayName("a redaction event listing a different row hash does not vouch for the row")
  void mismatchedListedHashFails() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRedaction event =
        new AuditChainRedaction(4L, REDACTION, Map.of(2L, "f".repeat(64)), 0, false);
    rows.set(1, redacted(rows.get(1), REDACTION));

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("a redaction event sealed before the row cannot vouch for it")
  void redactionEventMustFollowTheRow() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRedaction event =
        new AuditChainRedaction(1L, REDACTION, Map.of(2L, rows.get(1).rowHash()), 0, false);
    rows.set(1, redacted(rows.get(1), REDACTION));

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("linkage across a redacted row is still checked")
  void linkageAcrossRedactedRowIsChecked() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    // A forged row hash, listed by the event, no longer links to the following row.
    String forged = "e".repeat(64);
    AuditChainRow original = rows.get(1);
    AuditChainRow forgedRow =
        new AuditChainRow(
            original.seq(),
            original.prevHash(),
            forged,
            original.id(),
            original.scope(),
            original.documentId(),
            original.eventType(),
            null,
            "{}",
            original.createdAtMicros(),
            REDACTION);
    AuditChainRedaction event =
        new AuditChainRedaction(4L, REDACTION, Map.of(2L, forged), 0, false);
    rows.set(1, forgedRow);

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.LINK_MISMATCH, 3, 3));
  }

  @Test
  @DisplayName("a row redacted before sealing verifies by content when the event declares it")
  void pendingRedactedRowVerifiesByContent() {
    List<AuditChainRow> rows = new ArrayList<>(chain(1));
    AuditChainRow pending = sealed(2, rows.get(0).rowHash(), "event.2", "{\"userId\": \"x\"}");
    rows.add(
        new AuditChainRow(
            pending.seq(),
            pending.prevHash(),
            pending.rowHash(),
            pending.id(),
            pending.scope(),
            pending.documentId(),
            pending.eventType(),
            pending.actorId(),
            pending.detail(),
            pending.createdAtMicros(),
            REDACTION));
    rows.add(sealed(3, rows.get(1).rowHash(), "audit.chain.redacted", "{}"));
    AuditChainRedaction declared = new AuditChainRedaction(3L, REDACTION, Map.of(), 1, false);
    AuditChainRedaction undeclared =
        new AuditChainRedaction(3L, REDACTION, Map.of(9L, "x"), 0, false);

    assertThat(verifyRedacted(rows, List.of(declared)).problems()).isEmpty();
    assertThat(verifyRedacted(rows, List.of(undeclared)).problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("malformed and duplicate redaction events are reported")
  void malformedAndDuplicateRedactionEvents() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRedaction malformed = new AuditChainRedaction(4L, null, Map.of(), 0, true);
    AuditChainRedaction first = new AuditChainRedaction(4L, REDACTION, Map.of(1L, "a"), 0, false);
    AuditChainRedaction second = new AuditChainRedaction(5L, REDACTION, Map.of(1L, "a"), 0, false);

    Report report = verifyRedacted(rows, List.of(malformed, first, second));

    assertThat(report.problems())
        .containsExactly(
            new Problem(ProblemKind.BAD_REDACTION, 4, 4),
            new Problem(ProblemKind.BAD_REDACTION, 5, 5));
  }
}
