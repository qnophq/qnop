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
  private static final UUID SECOND = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0000000000bb");

  /** The row with pseudonymised content and redaction fields; chain columns untouched. */
  private static AuditChainRow redacted(AuditChainRow row, UUID redactionId, String detail) {
    return new AuditChainRow(
        row.seq(),
        row.prevHash(),
        row.rowHash(),
        row.id(),
        row.scope(),
        row.documentId(),
        row.eventType(),
        null,
        detail,
        row.createdAtMicros(),
        redactionId,
        "erased-user:x",
        5_000_000L);
  }

  private static AuditChainRow redacted(AuditChainRow row, UUID redactionId) {
    return redacted(row, redactionId, "{\"userId\": \"erased-user:x\"}");
  }

  /** What the database records for a sealed row it just redacted. */
  private static AuditChainRedaction.Listing listed(AuditChainRow redactedRow) {
    return new AuditChainRedaction.Listing(
        redactedRow.seq(),
        redactedRow.rowHash(),
        AuditChainHasher.contentHash(redactedRow.prevHash(), redactedRow));
  }

  private static AuditChainRedaction event(
      long recordedSeq, UUID redactionId, Map<UUID, AuditChainRedaction.Listing> rows) {
    return new AuditChainRedaction(recordedSeq, redactionId, rows, false);
  }

  private static Report verifyRedacted(
      List<AuditChainRow> rows, List<AuditChainRedaction> redactions) {
    AuditChainVerifier verifier = new AuditChainVerifier(List.of(), Set.of(), redactions);
    rows.forEach(verifier::accept);
    return verifier.finish(headOf(rows), 0, rows.size());
  }

  /** A chain of 3 rows plus the redaction event at seq 4. */
  private static List<AuditChainRow> chainWithRedactionEvent() {
    List<AuditChainRow> rows = new ArrayList<>(chain(3));
    rows.add(sealed(4, rows.get(2).rowHash(), "audit.chain.redacted", "{}"));
    return rows;
  }

  @Test
  @DisplayName("a redacted row its redaction event lists with matching hashes passes")
  void redactedRowWithMatchingEventPasses() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    rows.set(1, redacted(rows.get(1), REDACTION));

    Report report =
        verifyRedacted(
            rows, List.of(event(4, REDACTION, Map.of(rows.get(1).id(), listed(rows.get(1))))));

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
  @DisplayName("content changed after the redaction no longer matches the recorded content hash")
  void contentChangedAfterRedactionFails() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRow redactedRow = redacted(rows.get(1), REDACTION);
    AuditChainRedaction event = event(4, REDACTION, Map.of(redactedRow.id(), listed(redactedRow)));
    rows.set(1, redacted(rows.get(1), REDACTION, "{\"userId\": \"someone-else\"}"));

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("a listed row stripped of its redaction marker is reported, not recomputed")
  void strippedRedactionIsReported() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRow redactedRow = redacted(rows.get(1), REDACTION);
    AuditChainRedaction event = event(4, REDACTION, Map.of(redactedRow.id(), listed(redactedRow)));

    Report report = verifyRedacted(rows, List.of(event));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("a content change without any redaction stays a content mismatch")
  void unmarkedChangeIsContentMismatch() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRow original = rows.get(1);
    rows.set(
        1,
        new AuditChainRow(
            original.seq(),
            original.prevHash(),
            original.rowHash(),
            original.id(),
            original.scope(),
            original.documentId(),
            original.eventType(),
            null,
            "{}",
            original.createdAtMicros()));

    Report report = verifyRedacted(rows, List.of());

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.CONTENT_MISMATCH, 2, 2));
  }

  @Test
  @DisplayName("a listing with a different position or original hash does not vouch for the row")
  void mismatchedListingFails() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRow redactedRow = redacted(rows.get(1), REDACTION);
    rows.set(1, redactedRow);
    AuditChainRedaction.Listing good = listed(redactedRow);
    AuditChainRedaction.Listing otherHash =
        new AuditChainRedaction.Listing(good.seq(), "f".repeat(64), good.contentHash());
    AuditChainRedaction.Listing otherSeq =
        new AuditChainRedaction.Listing(3L, good.rowHash(), good.contentHash());

    assertThat(
            verifyRedacted(rows, List.of(event(4, REDACTION, Map.of(redactedRow.id(), otherHash))))
                .problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
    assertThat(
            verifyRedacted(rows, List.of(event(4, REDACTION, Map.of(redactedRow.id(), otherSeq))))
                .problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("a redaction event sealed before the row cannot vouch for it")
  void redactionEventMustFollowTheRow() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    rows.set(1, redacted(rows.get(1), REDACTION));

    Report report =
        verifyRedacted(
            rows, List.of(event(1, REDACTION, Map.of(rows.get(1).id(), listed(rows.get(1))))));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("linkage across a redacted row is still checked")
  void linkageAcrossRedactedRowIsChecked() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    AuditChainRow original = rows.get(1);
    // A forged stored hash, listed consistently, no longer links to the following row.
    AuditChainRow forged =
        redacted(
            new AuditChainRow(
                original.seq(),
                original.prevHash(),
                "e".repeat(64),
                original.id(),
                original.scope(),
                original.documentId(),
                original.eventType(),
                original.actorId(),
                original.detail(),
                original.createdAtMicros()),
            REDACTION);
    rows.set(1, forged);

    Report report =
        verifyRedacted(rows, List.of(event(4, REDACTION, Map.of(forged.id(), listed(forged)))));

    assertThat(report.problems()).containsExactly(new Problem(ProblemKind.LINK_MISMATCH, 3, 3));
  }

  @Test
  @DisplayName("a row redacted before sealing must reproduce its sealed hash and its content hash")
  void pendingRedactedRowVerifiesByContent() {
    List<AuditChainRow> rows = new ArrayList<>(chain(1));
    // Sealed at commit over its already-redacted content.
    AuditChainRow base =
        sealed(2, rows.get(0).rowHash(), "event.2", "{\"userId\": \"erased-user:x\"}");
    AuditChainRow pending = redacted(base, REDACTION, base.detail());
    rows.add(pending);
    rows.add(sealed(3, pending.rowHash(), "audit.chain.redacted", "{}"));
    AuditChainRedaction.Listing ok =
        new AuditChainRedaction.Listing(null, null, AuditChainHasher.contentHash("", pending));
    AuditChainRedaction.Listing wrong = new AuditChainRedaction.Listing(null, null, "0".repeat(64));

    assertThat(
            verifyRedacted(rows, List.of(event(3, REDACTION, Map.of(pending.id(), ok)))).problems())
        .isEmpty();
    assertThat(
            verifyRedacted(rows, List.of(event(3, REDACTION, Map.of(pending.id(), wrong))))
                .problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("a row naming two erased users is vouched for by the later redaction")
  void reRedactedRowFollowsTheLatestRedaction() {
    List<AuditChainRow> rows = new ArrayList<>(chain(3));
    AuditChainRow first = redacted(rows.get(1), REDACTION, "{\"a\": \"erased-user:x\"}");
    rows.add(sealed(4, rows.get(2).rowHash(), "audit.chain.redacted", "{}"));
    rows.add(sealed(5, rows.get(3).rowHash(), "audit.chain.redacted", "{}"));
    AuditChainRow second = redacted(rows.get(1), SECOND, "{\"a\": \"erased-user:y\"}");
    AuditChainRedaction a = event(4, REDACTION, Map.of(first.id(), listed(first)));
    AuditChainRedaction b = event(5, SECOND, Map.of(second.id(), listed(second)));

    rows.set(1, second);
    assertThat(verifyRedacted(rows, List.of(a, b)).problems()).isEmpty();

    // Rolled back to the first redaction's state: the later listing no longer matches.
    rows.set(1, first);
    assertThat(verifyRedacted(rows, List.of(a, b)).problems())
        .containsExactly(new Problem(ProblemKind.BAD_REDACTION, 2, 2));
  }

  @Test
  @DisplayName("malformed and duplicate redaction events neither vouch nor fail the walk")
  void malformedAndDuplicateRedactionEventsAreIgnored() {
    List<AuditChainRow> rows = chainWithRedactionEvent();
    rows.add(sealed(5, rows.get(3).rowHash(), "audit.chain.redacted", "{}"));
    AuditChainRow redactedRow = redacted(rows.get(1), REDACTION);
    rows.set(1, redactedRow);
    AuditChainRedaction genuine =
        event(4, REDACTION, Map.of(redactedRow.id(), listed(redactedRow)));
    AuditChainRedaction duplicate =
        event(
            5, REDACTION, Map.of(redactedRow.id(), new AuditChainRedaction.Listing(2L, "a", "b")));
    AuditChainRedaction malformed = new AuditChainRedaction(5L, null, Map.of(), true);

    Report report = verifyRedacted(rows, List.of(genuine, duplicate, malformed));

    assertThat(report.problems()).isEmpty();
    assertThat(report.intact()).isTrue();
  }
}
