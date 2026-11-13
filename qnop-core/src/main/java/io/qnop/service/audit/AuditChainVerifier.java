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

import io.qnop.repository.AuditChainHead;
import io.qnop.repository.AuditChainPrunedRange;
import io.qnop.repository.AuditChainRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Walks the audit hash chain in order and reports every place it does not hold (issue #928,
 * ADR-0043). Plain, DB-free code — the service feeds it rows batch by batch — so the whole decision
 * logic is unit-testable (the architecture guardrail).
 *
 * <p>Checks, per sealed row: its position follows on from the previous one (a hole is acceptable
 * only when a tombstone range covers it exactly and links up on both sides); its {@code prevHash}
 * equals the previous link's hash; and its {@code rowHash} equals the hash recomputed from its
 * content. At the end the walk must arrive exactly at the recorded chain head, no committed row may
 * be unsealed, and the rows walked plus the unsealed ones must account for every row in the table.
 *
 * <p>Tombstones are checked too: a malformed one is a {@link ProblemKind#BAD_PRUNE_RANGE} (its
 * ranges bridge nothing), and every document it names must have its deletion explained by a {@code
 * review.deleted}/{@code review.purged} event — otherwise {@link ProblemKind#UNEXPLAINED_PRUNE}.
 *
 * <p>Redacted rows (a user erasure pseudonymised them, issue #952) are not recomputed — their
 * content changed on purpose. Such a row is accepted only when the {@code audit.chain.redacted}
 * event of its redaction, sealed after it, lists its position with exactly its stored hash; or, for
 * a row redacted before it was sealed, when that event declares pending rows and the row's content
 * reproduces its hash. Anything else — no such event, a different hash, a malformed or duplicate
 * redaction event — is a {@link ProblemKind#BAD_REDACTION}. The link check across a redacted row is
 * unchanged, so a redaction can never splice or reorder the chain.
 *
 * <p>After a mismatch the walk continues from the stored hash, so one altered row is reported once
 * rather than as a cascade of every later link.
 */
public final class AuditChainVerifier {

  /** At most this many problems are kept in the report; {@link Report#problemCount} has all. */
  static final int MAX_REPORTED_PROBLEMS = 20;

  /** What kind of break was found. */
  public enum ProblemKind {
    /** Chain positions missing with no tombstone accounting for them. */
    UNSANCTIONED_GAP,
    /** A tombstone range whose boundary hash does not link to the surviving chain. */
    BAD_PRUNE_RANGE,
    /** A row's {@code prevHash} is not the previous link's hash. */
    LINK_MISMATCH,
    /** A row's content no longer produces its stored hash. */
    CONTENT_MISMATCH,
    /** The walk does not end on the recorded chain head (rows removed from the end, or forged). */
    HEAD_MISMATCH,
    /** Committed rows that were never sealed, or carry an impossible position (seq < 1). */
    UNSEALED_ROWS,
    /** The table holds rows the walk did not account for. */
    ROW_COUNT_MISMATCH,
    /** A tombstone names a document whose deletion no review-deleted/purged event explains. */
    UNEXPLAINED_PRUNE,
    /**
     * A redacted row no redaction event vouches for, or a malformed/duplicate redaction event
     * (issue #952).
     */
    BAD_REDACTION,
    /** The verification itself failed before reaching a verdict. */
    VERIFICATION_ERROR
  }

  /** One break, located by the chain positions it concerns ({@code count} for unsealed rows). */
  public record Problem(ProblemKind kind, long fromSeq, long toSeq) {}

  /** The outcome of one walk. */
  public record Report(
      boolean intact,
      long rowsChecked,
      long headSeq,
      String headHash,
      List<Problem> problems,
      int problemCount) {

    public Report {
      problems = List.copyOf(problems);
    }

    /** The report for a run that failed before reaching a verdict — never "intact". */
    public static Report failed() {
      return new Report(
          false, 0, -1, null, List.of(new Problem(ProblemKind.VERIFICATION_ERROR, 0, 0)), 1);
    }
  }

  private final Map<Long, AuditChainPrunedRange> rangesByStart = new HashMap<>();
  private final Map<UUID, AuditChainRedaction> redactionsById = new HashMap<>();
  private final List<Problem> problems = new ArrayList<>();
  private int problemCount;
  private long expectedSeq = 1;
  private String expectedPrev = AuditChainHasher.GENESIS;

  /**
   * True while {@link #expectedPrev} is unknown because the walk just crossed an unexplained gap.
   */
  private boolean linkUnknown;

  private long rowsChecked;

  /**
   * @param tombstones every parsed {@code audit.chain.pruned} tombstone. A range is only honoured
   *     if its tombstone parsed cleanly, the range is well-formed, and the tombstone was sealed
   *     <em>after</em> the rows it accounts for — a tombstone cannot vouch for a future it had not
   *     seen.
   * @param explainedDocumentIds ids of documents whose deletion a review-deleted/purged event
   *     records
   */
  public AuditChainVerifier(
      Collection<AuditChainTombstone> tombstones, Set<String> explainedDocumentIds) {
    this(tombstones, explainedDocumentIds, List.of());
  }

  /**
   * @param redactions every parsed {@code audit.chain.redacted} event, in chain order. A malformed
   *     one, or a second one for an already-seen redaction id, is reported and vouches for nothing.
   */
  public AuditChainVerifier(
      Collection<AuditChainTombstone> tombstones,
      Set<String> explainedDocumentIds,
      Collection<AuditChainRedaction> redactions) {
    for (AuditChainRedaction redaction : redactions) {
      long at = redaction.recordedSeq() == null ? 0 : redaction.recordedSeq();
      if (redaction.malformed() || redactionsById.containsKey(redaction.redactionId())) {
        report(ProblemKind.BAD_REDACTION, at, at);
        continue;
      }
      redactionsById.put(redaction.redactionId(), redaction);
    }
    for (AuditChainTombstone tombstone : tombstones) {
      long at = tombstone.recordedSeq() == null ? 0 : tombstone.recordedSeq();
      if (tombstone.malformed()) {
        report(ProblemKind.BAD_PRUNE_RANGE, at, at);
        continue;
      }
      for (String documentId : tombstone.documentIds()) {
        if (!explainedDocumentIds.contains(documentId)) {
          report(ProblemKind.UNEXPLAINED_PRUNE, at, at);
        }
      }
      for (AuditChainPrunedRange range : tombstone.ranges()) {
        boolean wellFormed =
            range.fromSeq() >= 1
                && range.toSeq() >= range.fromSeq()
                && range.recordedSeq() != null
                && range.recordedSeq() > range.toSeq();
        if (!wellFormed) {
          continue;
        }
        if (rangesByStart.containsKey(range.fromSeq())) {
          // Only one deletion can remove a given position; a second claim is suspect.
          // Tombstones arrive in chain order, so the earliest claim is the one kept.
          report(ProblemKind.BAD_PRUNE_RANGE, range.fromSeq(), range.toSeq());
        } else {
          rangesByStart.put(range.fromSeq(), range);
        }
      }
    }
  }

  /** Checks the next sealed row; rows must arrive in ascending {@code seq} order. */
  public void accept(AuditChainRow row) {
    bridgeTo(row.seq());
    if (!linkUnknown && !expectedPrev.equals(row.prevHash())) {
      report(ProblemKind.LINK_MISMATCH, row.seq(), row.seq());
    }
    if (row.redactionId() != null) {
      if (!redactionVouchesFor(row)) {
        report(ProblemKind.BAD_REDACTION, row.seq(), row.seq());
      }
    } else if (!contentMatches(row)) {
      report(ProblemKind.CONTENT_MISMATCH, row.seq(), row.seq());
    }
    expectedPrev = row.rowHash();
    expectedSeq = row.seq() + 1;
    linkUnknown = false;
    rowsChecked++;
  }

  /**
   * Closes the walk against the chain head, the unsealed-row count and the table's total row count,
   * and builds the report.
   */
  public Report finish(AuditChainHead head, long unsealedRows, long totalRows) {
    if (head.lastSeq() + 1 < expectedSeq) {
      // Rows exist beyond the recorded head: the head was rewound.
      report(ProblemKind.HEAD_MISMATCH, head.lastSeq(), expectedSeq - 1);
    } else {
      bridgeTo(head.lastSeq() + 1);
      if (!linkUnknown && !expectedPrev.equals(head.lastHash())) {
        report(ProblemKind.HEAD_MISMATCH, head.lastSeq(), head.lastSeq());
      }
    }
    if (unsealedRows > 0) {
      report(ProblemKind.UNSEALED_ROWS, unsealedRows, unsealedRows);
    }
    if (totalRows != rowsChecked + unsealedRows) {
      report(ProblemKind.ROW_COUNT_MISMATCH, rowsChecked + unsealedRows, totalRows);
    }
    return new Report(
        problemCount == 0, rowsChecked, head.lastSeq(), head.lastHash(), problems, problemCount);
  }

  private static boolean contentMatches(AuditChainRow row) {
    String recomputed = AuditChainHasher.linkHash(row.prevHash(), AuditChainHasher.canonical(row));
    return recomputed.equals(row.rowHash());
  }

  /**
   * Whether the row's redaction event, sealed after the row, lists it with its exact stored hash —
   * or, for a row redacted before it was sealed, declares pending rows and the row's (redacted)
   * content reproduces its hash.
   */
  private boolean redactionVouchesFor(AuditChainRow row) {
    AuditChainRedaction redaction = redactionsById.get(row.redactionId());
    if (redaction == null
        || redaction.recordedSeq() == null
        || redaction.recordedSeq() <= row.seq()) {
      return false;
    }
    String listed = redaction.rows().get(row.seq());
    if (listed != null) {
      return listed.equals(row.rowHash());
    }
    return redaction.pendingRows() > 0 && contentMatches(row);
  }

  /** Advances over missing positions up to (excluding) {@code nextSeq} using tombstone ranges. */
  private void bridgeTo(long nextSeq) {
    while (expectedSeq < nextSeq) {
      AuditChainPrunedRange range = rangesByStart.get(expectedSeq);
      if (range == null || range.toSeq() >= nextSeq) {
        report(ProblemKind.UNSANCTIONED_GAP, expectedSeq, nextSeq - 1);
        expectedSeq = nextSeq;
        linkUnknown = true;
        return;
      }
      if (!linkUnknown && !expectedPrev.equals(range.prevHash())) {
        report(ProblemKind.BAD_PRUNE_RANGE, range.fromSeq(), range.toSeq());
      }
      expectedPrev = range.lastHash();
      expectedSeq = range.toSeq() + 1;
      linkUnknown = false;
    }
  }

  private void report(ProblemKind kind, long fromSeq, long toSeq) {
    problemCount++;
    if (problems.size() < MAX_REPORTED_PROBLEMS) {
      problems.add(new Problem(kind, fromSeq, toSeq));
    }
  }
}
