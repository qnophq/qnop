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
package io.qnop.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.service.storage.StorageConsistencyService.ConsistencyReport;
import io.qnop.service.storage.StorageConsistencyService.ConsistencySummary;
import io.qnop.service.storage.StorageConsistencyService.OrphanView;
import io.qnop.service.storage.StorageRemediationService.RemediationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The scheduled bucket orphan reaper's guards (issue #523, ADR-0043): opt-in, dry-run, grace, cap.
 */
@ExtendWith(MockitoExtension.class)
class StorageOrphanReaperTest {

  private static final Duration GRACE = Duration.ofHours(24);

  @Mock private StorageConsistencyService scanner;
  @Mock private StorageRemediationService remediation;

  private static S3Properties props(boolean enabled, boolean dryRun, int maxDeletes) {
    return new S3Properties(
        null,
        null,
        "bucket",
        "ak",
        "sk",
        null,
        null,
        null,
        null,
        null,
        null,
        enabled,
        dryRun,
        GRACE,
        maxDeletes);
  }

  private static OrphanView orphan(String key, Duration age) {
    return new OrphanView(key, 1024L, Instant.now().minus(age));
  }

  private static ConsistencyReport report(OrphanView... orphans) {
    return new ConsistencyReport(
        new ConsistencySummary(0, 0, 0, orphans.length, Instant.now()),
        List.of(),
        List.of(orphans));
  }

  @Test
  @DisplayName("does nothing (not even a scan) when the reaper is disabled")
  void disabledDoesNothing() {
    StorageOrphanReaper reaper =
        new StorageOrphanReaper(scanner, remediation, props(false, true, 100));

    reaper.reap();

    verifyNoInteractions(scanner, remediation);
  }

  @Test
  @DisplayName("dry-run scans but never deletes")
  void dryRunNeverDeletes() {
    when(scanner.scan()).thenReturn(report(orphan("old", Duration.ofHours(48))));
    StorageOrphanReaper reaper =
        new StorageOrphanReaper(scanner, remediation, props(true, true, 100));

    reaper.reap();

    verify(scanner).scan();
    verifyNoInteractions(remediation);
  }

  @Test
  @DisplayName("deletes only orphans older than the grace period, as the system actor")
  void deletesOnlyAgedOrphans() {
    when(scanner.scan())
        .thenReturn(
            report(
                orphan("aged", Duration.ofHours(48)), // older than 24h grace → eligible
                orphan("fresh", Duration.ofHours(1)))); // within grace → protected
    when(remediation.deleteOrphans(any(), isNull()))
        .thenReturn(new RemediationResult(List.of("aged"), List.of()));
    StorageOrphanReaper reaper =
        new StorageOrphanReaper(scanner, remediation, props(true, false, 100));

    reaper.reap();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(remediation).deleteOrphans(keys.capture(), isNull());
    assertThat(keys.getValue()).containsExactly("aged");
  }

  @Test
  @DisplayName("never exceeds the per-tick delete cap")
  void respectsMaxDeletes() {
    when(scanner.scan())
        .thenReturn(
            report(
                orphan("a", Duration.ofHours(48)),
                orphan("b", Duration.ofHours(48)),
                orphan("c", Duration.ofHours(48))));
    when(remediation.deleteOrphans(any(), isNull()))
        .thenReturn(new RemediationResult(List.of(), List.of()));
    StorageOrphanReaper reaper =
        new StorageOrphanReaper(scanner, remediation, props(true, false, 2));

    reaper.reap();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(remediation).deleteOrphans(keys.capture(), isNull());
    assertThat(keys.getValue()).hasSize(2);
  }

  @Test
  @DisplayName("skips deletion when nothing is aged enough")
  void noEligibleNoDelete() {
    when(scanner.scan()).thenReturn(report(orphan("fresh", Duration.ofMinutes(30))));
    StorageOrphanReaper reaper =
        new StorageOrphanReaper(scanner, remediation, props(true, false, 100));

    reaper.reap();

    verify(remediation, never()).deleteOrphans(any(), any());
  }
}
