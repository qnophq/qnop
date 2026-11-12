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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.SchedulerJob;
import io.qnop.repository.AuditChainHead;
import io.qnop.repository.AuditChainRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.SchedulerJobRepository;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class AuditChainVerificationServiceTest {

  private final AuditChainRepository chain = mock(AuditChainRepository.class);
  private final SchedulerJobRepository schedulerJobs = mock(SchedulerJobRepository.class);
  private AuditChainVerificationService service;

  @BeforeEach
  void setUp() {
    service =
        new AuditChainVerificationService(
            chain,
            mock(AuditEventRepository.class),
            mock(SchedulerService.class),
            schedulerJobs,
            mock(PlatformTransactionManager.class));
  }

  private void intactEmptyChain() {
    when(chain.findTombstones()).thenReturn(List.of());
    when(chain.findExplainedDocumentIds()).thenReturn(Set.of());
    when(chain.findSealedAfter(0, 1000)).thenReturn(List.of());
    when(chain.findHead()).thenReturn(new AuditChainHead(0, AuditChainHasher.GENESIS));
  }

  @Test
  @DisplayName("a run that throws replaces an earlier intact verdict with a failed one")
  void failureIsNeverReportedAsIntact() {
    intactEmptyChain();
    assertThat(service.verify().intact()).isTrue();
    assertThat(service.lastKnownIntact()).contains(true);

    when(chain.findHead()).thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(service::verify).isInstanceOf(IllegalStateException.class);
    assertThat(service.lastReport()).hasValueSatisfying(r -> assertThat(r.intact()).isFalse());
    assertThat(service.lastKnownIntact()).contains(false);
  }

  @Test
  @DisplayName("before any local run, the persisted job outcome is the last known verdict")
  void fallsBackToPersistedOutcome() {
    SchedulerJob job = mock(SchedulerJob.class);
    when(job.getLastOutcome()).thenReturn("FAILURE");
    when(schedulerJobs.findById(SchedulerJobCatalog.AUDIT_CHAIN_VERIFY))
        .thenReturn(Optional.of(job));

    assertThat(service.lastKnownIntact()).contains(false);
  }

  /** Another application instance sharing the same database (repositories). */
  private AuditChainVerificationService otherInstance() {
    return new AuditChainVerificationService(
        chain,
        mock(AuditEventRepository.class),
        mock(SchedulerService.class),
        schedulerJobs,
        mock(PlatformTransactionManager.class));
  }

  private void persisted(String outcome, Instant at) {
    SchedulerJob job = mock(SchedulerJob.class);
    when(job.getLastOutcome()).thenReturn(outcome);
    when(job.getLastRunAt()).thenReturn(at);
    when(schedulerJobs.findById(SchedulerJobCatalog.AUDIT_CHAIN_VERIFY))
        .thenReturn(Optional.of(job));
  }

  @Test
  @DisplayName("an earlier local 'intact' yields to a newer failure recorded by another instance")
  void newerPersistedFailureWins() {
    intactEmptyChain();
    AuditChainVerificationService instanceA = service;
    assertThat(instanceA.verify().intact()).isTrue();

    // Instance B runs later, finds a break, and the scheduler persists FAILURE.
    persisted("FAILURE", Instant.now().plusSeconds(60));

    assertThat(instanceA.lastKnownIntact()).contains(false);
    assertThat(otherInstance().lastKnownIntact()).contains(false);
  }

  @Test
  @DisplayName("a newer local failure is not masked by an older persisted success")
  void newerLocalFailureWins() {
    persisted("SUCCESS", Instant.now().minusSeconds(3600));
    when(chain.findHead()).thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(service::verify).isInstanceOf(IllegalStateException.class);

    assertThat(service.lastKnownIntact()).contains(false);
    assertThat(otherInstance().lastKnownIntact()).contains(true);
  }

  @Test
  @DisplayName("a newer persisted success supersedes an older local failure")
  void newerPersistedSuccessWins() {
    when(chain.findHead()).thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(service::verify).isInstanceOf(IllegalStateException.class);

    persisted("SUCCESS", Instant.now().plusSeconds(60));

    assertThat(service.lastKnownIntact()).contains(true);
  }

  @Test
  @DisplayName("if the persisted outcome cannot be read, the local verdict stands")
  void persistedLookupFailureFallsBackToLocal() {
    intactEmptyChain();
    service.verify();
    when(schedulerJobs.findById(SchedulerJobCatalog.AUDIT_CHAIN_VERIFY))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThat(service.lastKnownIntact()).contains(true);
  }

  @Test
  @DisplayName("on a tie a failure wins over a success")
  void tieFavoursFailure() {
    Instant at = Instant.now();
    var ok = Optional.of(new AuditChainVerificationService.Verdict(true, at));
    var broken = Optional.of(new AuditChainVerificationService.Verdict(false, at));

    assertThat(AuditChainVerificationService.newer(ok, broken)).isEqualTo(broken);
    assertThat(AuditChainVerificationService.newer(broken, ok)).isEqualTo(broken);
  }

  @Test
  @DisplayName("with no run known anywhere the verdict is unknown")
  void unknownWithoutAnyRun() {
    when(schedulerJobs.findById(SchedulerJobCatalog.AUDIT_CHAIN_VERIFY))
        .thenReturn(Optional.empty());

    assertThat(service.lastKnownIntact()).isEmpty();
  }
}
