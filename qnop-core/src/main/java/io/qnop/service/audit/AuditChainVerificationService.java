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

import io.qnop.entity.AuditEvent;
import io.qnop.repository.AuditChainRepository;
import io.qnop.repository.AuditChainRow;
import io.qnop.repository.AuditEventRepository;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the audit hash chain end to end (issue #928, ADR-0043) as a catalogued scheduler job —
 * nightly, and on demand through the scheduler dashboard's run-now.
 *
 * <p>The walk runs in one read-only {@code REPEATABLE READ} transaction, so every batch, the
 * tombstones and the chain head come from a single snapshot. An intact chain is logged with its
 * head position and hash: that line is the checkpoint an operator ships to an external log store,
 * which is what makes a wholesale rewrite by someone holding the database credentials detectable. A
 * broken chain is logged at ERROR, recorded as an {@code audit.chain.broken} SYSTEM event, and
 * fails the run so the dashboard shows it; the latest report also feeds the {@code
 * qnop.audit.chain.intact} gauge.
 */
@Service
public class AuditChainVerificationService {

  /** SYSTEM audit event written when a verification run finds a break. */
  public static final String AUDIT_CHAIN_BROKEN = "audit.chain.broken";

  private static final Logger log = LoggerFactory.getLogger(AuditChainVerificationService.class);

  private static final int BATCH_SIZE = 1000;

  private final AuditChainRepository chain;
  private final AuditEventRepository auditEvents;
  private final SchedulerService scheduler;
  private final TransactionTemplate snapshotTx;
  private final TransactionTemplate writeTx;

  private volatile AuditChainVerifier.Report lastReport;

  public AuditChainVerificationService(
      AuditChainRepository chain,
      AuditEventRepository auditEvents,
      SchedulerService scheduler,
      PlatformTransactionManager transactionManager) {
    this.chain = chain;
    this.auditEvents = auditEvents;
    this.scheduler = scheduler;
    this.snapshotTx = new TransactionTemplate(transactionManager);
    this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshotTx.setReadOnly(true);
    this.writeTx = new TransactionTemplate(transactionManager);
  }

  /** Nightly cron entry point; the gate runs {@link #verifyOnce}. */
  @Scheduled(cron = "${qnop.audit.chain-verify-cron:0 0 5 * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.AUDIT_CHAIN_VERIFY, lockAtMostFor = "PT1H")
  public void verifyScheduled() {
    scheduler.runScheduled(SchedulerJobCatalog.AUDIT_CHAIN_VERIFY);
  }

  /** The most recent report produced on this instance, if any run has completed here. */
  public Optional<AuditChainVerifier.Report> lastReport() {
    return Optional.ofNullable(lastReport);
  }

  /**
   * Walks the whole chain and returns the report, without side effects beyond {@link #lastReport}.
   */
  public AuditChainVerifier.Report verify() {
    AuditChainVerifier.Report report = snapshotTx.execute(status -> walk());
    lastReport = report;
    return report;
  }

  /**
   * The scheduler unit of work (self-transactional; dry-run is meaningless for a read-only check
   * and ignored).
   *
   * @throws AuditChainBrokenException when the chain does not verify, after recording it
   */
  public String verifyOnce(boolean dryRun) {
    AuditChainVerifier.Report report = verify();
    if (report.intact()) {
      log.info(
          "Audit chain intact: {} row(s) verified, head seq={} hash={}",
          report.rowsChecked(),
          report.headSeq(),
          report.headHash());
      return "Chain intact: "
          + report.rowsChecked()
          + " row(s), head "
          + report.headSeq()
          + " "
          + report.headHash();
    }
    log.error(
        "Audit chain verification FAILED: {} problem(s), first {}; head seq={} hash={}",
        report.problemCount(),
        report.problems(),
        report.headSeq(),
        report.headHash());
    writeTx.executeWithoutResult(
        status -> auditEvents.save(AuditEvent.system(AUDIT_CHAIN_BROKEN, null, detail(report))));
    throw new AuditChainBrokenException(report.problemCount());
  }

  private AuditChainVerifier.Report walk() {
    AuditChainVerifier verifier = new AuditChainVerifier(chain.findPrunedRanges());
    long after = 0;
    while (true) {
      List<AuditChainRow> batch = chain.findSealedAfter(after, BATCH_SIZE);
      batch.forEach(verifier::accept);
      if (batch.size() < BATCH_SIZE) {
        break;
      }
      after = batch.getLast().seq();
    }
    return verifier.finish(chain.findHead(), chain.countUnsealed());
  }

  /** The SYSTEM event detail; every value is a number, an enum name or a hex hash — no escaping. */
  static String detail(AuditChainVerifier.Report report) {
    String problems =
        report.problems().stream()
            .map(
                p ->
                    "{\"kind\":\""
                        + p.kind().name()
                        + "\",\"fromSeq\":"
                        + p.fromSeq()
                        + ",\"toSeq\":"
                        + p.toSeq()
                        + "}")
            .collect(Collectors.joining(",", "[", "]"));
    return "{\"problemCount\":"
        + report.problemCount()
        + ",\"rowsChecked\":"
        + report.rowsChecked()
        + ",\"headSeq\":"
        + report.headSeq()
        + ",\"headHash\":\""
        + report.headHash()
        + "\",\"problems\":"
        + problems
        + "}";
  }
}
