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

/**
 * Walks the audit hash chain in order and reports every place it does not hold (issue #928,
 * ADR-0043). Plain, DB-free code — the service feeds it rows batch by batch — so the whole decision
 * logic is unit-testable (the architecture guardrail).
 *
 * <p>Checks, per sealed row: its position follows on from the previous one (a hole is acceptable
 * only when a tombstone range covers it exactly and links up on both sides); its {@code prevHash}
 * equals the previous link's hash; and its {@code rowHash} equals the hash recomputed from its
 * content. At the end the walk must arrive exactly at the recorded chain head, and no committed row
 * may be unsealed.
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
    /** Committed rows that were never sealed (the sealing trigger was bypassed). */
    UNSEALED_ROWS
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
  }

  private final Map<Long, AuditChainPrunedRange> rangesByStart = new HashMap<>();
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
   * @param prunedRanges every range recorded by an {@code audit.chain.pruned} tombstone. A range is
   *     only honoured if it is well-formed and its tombstone was sealed <em>after</em> the rows it
   *     accounts for — a tombstone cannot vouch for a future it had not seen.
   */
  public AuditChainVerifier(Collection<AuditChainPrunedRange> prunedRanges) {
    for (AuditChainPrunedRange range : prunedRanges) {
      boolean wellFormed =
          range.fromSeq() >= 1
              && range.toSeq() >= range.fromSeq()
              && range.prevHash() != null
              && range.lastHash() != null
              && range.recordedSeq() != null
              && range.recordedSeq() > range.toSeq();
      if (wellFormed) {
        rangesByStart.putIfAbsent(range.fromSeq(), range);
      }
    }
  }

  /** Checks the next sealed row; rows must arrive in ascending {@code seq} order. */
  public void accept(AuditChainRow row) {
    bridgeTo(row.seq());
    if (!linkUnknown && !expectedPrev.equals(row.prevHash())) {
      report(ProblemKind.LINK_MISMATCH, row.seq(), row.seq());
    }
    String recomputed = AuditChainHasher.linkHash(row.prevHash(), AuditChainHasher.canonical(row));
    if (!recomputed.equals(row.rowHash())) {
      report(ProblemKind.CONTENT_MISMATCH, row.seq(), row.seq());
    }
    expectedPrev = row.rowHash();
    expectedSeq = row.seq() + 1;
    linkUnknown = false;
    rowsChecked++;
  }

  /** Closes the walk against the chain head and the unsealed-row count, and builds the report. */
  public Report finish(AuditChainHead head, long unsealedRows) {
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
    return new Report(
        problemCount == 0, rowsChecked, head.lastSeq(), head.lastHash(), problems, problemCount);
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
