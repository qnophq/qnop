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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.entity.StorageObject;
import io.qnop.entity.StorageObjectStatus;
import io.qnop.observability.metrics.StorageMeters;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * DB-free unit tests for {@link StorageService#stage} dedup correctness. A registry hit is only a
 * dedup hint: the object's real existence is verified before the upload is skipped, and it is
 * (re-)uploaded from the buffered bytes whenever it is missing — whether the row is a poisoned
 * {@code PENDING} one (issue #289) or a {@code COMMITTED} one whose object vanished out of band
 * (issue #575) — so a stale row can never leave a key pointing at nothing.
 */
class StorageServiceTest {

  private final StorageProvider provider = mock(StorageProvider.class);
  private final StorageObjectRepository repository = mock(StorageObjectRepository.class);
  private final S3Properties properties = mock(S3Properties.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final PlatformTransactionManager transactionManager = transactionManager();
  private final StorageService storage =
      new StorageService(
          provider,
          repository,
          properties,
          mock(SchedulerService.class),
          new StorageMeters(registry),
          transactionManager);

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return manager;
  }

  private double reaped() {
    return registry.counter(StorageMeters.ORPHANS_REAPED).count();
  }

  private void orphansAreDue(StorageObject... orphans) {
    when(properties.reaperGracePeriod()).thenReturn(Duration.ofHours(1));
    when(repository.findByStatusAndCreatedAtBefore(eq(StorageObjectStatus.PENDING), any()))
        .thenReturn(List.of(orphans));
  }

  @Test
  @DisplayName("a dry run of the reaper deletes nothing and counts nothing (issue #962)")
  void dryRunCountsNothing() {
    orphansAreDue(orphan("a"));

    storage.reapOrphansOnce(true);

    assertThat(reaped()).isZero();
    verify(repository, never()).deleteIfOrphaned(any(), any(), any());
    verify(provider, never()).delete(anyString());
  }

  @Test
  @DisplayName("the reaper deletes only the objects whose rows it claimed, and counts only those")
  void reaperDeletesOnlyClaimedOrphans() {
    StorageObject claimed = orphan("claimed");
    StorageObject committedMeanwhile = orphan("committed");
    orphansAreDue(claimed, committedMeanwhile);
    when(repository.deleteIfOrphaned(eq(claimed.getId()), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(1);
    when(repository.deleteIfOrphaned(
            eq(committedMeanwhile.getId()), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(0);

    String summary = storage.reapOrphansOnce(false);

    // An upload committed the second row after the reaper read it (issue #987): its object stays.
    verify(provider).delete("claimed");
    verify(provider, never()).delete("committed");
    assertThat(reaped()).isEqualTo(1.0);
    assertThat(summary).isEqualTo("Deleted 1 uncommitted object(s)");
  }

  @Test
  @DisplayName("an orphan whose object cannot be deleted keeps its row; the others are reaped")
  void aFailedOrphanRollsBackAloneAndFailsTheRun() {
    StorageObject stuck = orphan("stuck");
    StorageObject reapable = orphan("reapable");
    orphansAreDue(stuck, reapable);
    when(repository.deleteIfOrphaned(any(), any(), any())).thenReturn(1);
    doThrow(new StorageException("bucket unavailable")).when(provider).delete("stuck");

    assertThatThrownBy(() -> storage.reapOrphansOnce(false))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("1 could not be deleted");

    // One transaction per orphan: the stuck one's claim is rolled back, the other one committed.
    verify(transactionManager).rollback(any());
    verify(transactionManager).commit(any());
    verify(provider).delete("reapable");
    assertThat(reaped()).isEqualTo(1.0);
  }

  private static InputStream content() {
    return new ByteArrayInputStream("some qnop document bytes".getBytes());
  }

  private static StorageObject pendingRow() {
    return StorageObject.pending("k", "h", "application/pdf", 1L);
  }

  private static StorageObject orphan(String key) {
    StorageObject row = StorageObject.pending(key, "h", "application/pdf", 1L);
    ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
    return row;
  }

  private static StorageObject committedRow() {
    StorageObject row = pendingRow();
    // Production commits through a conditional update (issue #982), not through the entity.
    ReflectionTestUtils.setField(row, "status", StorageObjectStatus.COMMITTED);
    ReflectionTestUtils.setField(row, "committedAt", Instant.now());
    return row;
  }

  @Test
  @DisplayName("a commit that changes a row needs no second look (issue #982)")
  void commitOfAPendingRowDoesNotLookTheRowUp() {
    when(repository.commitIfPending(eq("k"), any(), any(), any())).thenReturn(1);

    storage.commit("k");

    verify(repository)
        .commitIfPending(
            eq("k"), any(), eq(StorageObjectStatus.PENDING), eq(StorageObjectStatus.COMMITTED));
    verify(repository, never()).findByObjectKey(anyString());
  }

  @Test
  @DisplayName("a commit that changes nothing tells an already committed row from a vanished one")
  void commitThatChangesNothingChecksWhetherTheRowStillExists() {
    when(repository.commitIfPending(eq("k"), any(), any(), any())).thenReturn(0);
    when(repository.findByObjectKey("k")).thenReturn(Optional.empty());

    storage.commit("k"); // logs, never throws: the domain row is already durable

    verify(repository).findByObjectKey("k");
  }

  @Test
  @DisplayName("staging aborts over the byte limit before any upload (issue #361)")
  void overLimitContentIsRejectedBeforeUpload() {
    InputStream tenBytes = new ByteArrayInputStream("0123456789".getBytes());

    Assertions.assertThatThrownBy(() -> storage.stage(tenBytes, "application/pdf", 4L))
        .isInstanceOf(StorageQuotaExceededException.class);

    verify(provider, never()).put(anyString(), any(), anyLong(), anyString());
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("content exactly at the byte limit still stages")
  void contentAtLimitIsStaged() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.empty());
    InputStream fourBytes = new ByteArrayInputStream("abcd".getBytes());

    storage.stage(fourBytes, "application/pdf", 4L);

    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }

  @Test
  @DisplayName("a COMMITTED hit whose object exists dedups without uploading")
  void committedRowWithExistingObjectDedups() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(committedRow()));
    when(provider.exists(anyString())).thenReturn(true);

    storage.stage(content(), "application/pdf");

    // The object is verified present, then the upload is skipped and the row left untouched.
    verify(provider).exists(anyString());
    verify(provider, never()).put(anyString(), any(), anyLong(), anyString());
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("a COMMITTED hit whose object vanished out of band re-uploads — the #575 fix")
  void committedRowWithMissingObjectIsReuploaded() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(committedRow()));
    when(provider.exists(anyString())).thenReturn(false);

    storage.stage(content(), "application/pdf");

    // The COMMITTED row is no longer trusted blindly: the absent object is re-materialized from
    // the buffered bytes, and the row is not re-inserted (it already exists and stays COMMITTED).
    verify(provider).exists(anyString());
    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("a poisoned PENDING hit (object missing) re-uploads — the fix")
  void pendingRowWithMissingObjectIsReuploaded() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(pendingRow()));
    when(repository.restageIfPending(anyString(), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(1);
    when(provider.exists(anyString())).thenReturn(false);

    storage.stage(content(), "application/pdf");

    verify(provider).exists(anyString());
    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
    verify(repository, never()).save(any()); // row already exists — not re-inserted
  }

  @Test
  @DisplayName("a PENDING hit whose object exists (mid-flight) is not re-uploaded")
  void pendingRowWithExistingObjectIsNotReuploaded() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(pendingRow()));
    when(repository.restageIfPending(anyString(), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(1);
    when(provider.exists(anyString())).thenReturn(true);

    storage.stage(content(), "application/pdf");

    verify(provider).exists(anyString());
    verify(provider, never()).put(anyString(), any(), anyLong(), anyString());
  }

  @Test
  @DisplayName("a reused PENDING row is marked as staged now, so the reaper leaves it (issue #987)")
  void reusedPendingRowIsRestaged() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(pendingRow()));
    when(repository.restageIfPending(anyString(), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(1);
    when(provider.exists(anyString())).thenReturn(true);
    Instant before = Instant.now();

    storage.stage(content(), "application/pdf");

    verify(repository)
        .restageIfPending(
            anyString(), argThat(at -> !at.isBefore(before)), eq(StorageObjectStatus.PENDING));
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("a PENDING row reaped after it was read is inserted and uploaded again (issue #987)")
  void pendingRowReapedMeanwhileIsInsertedAgain() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(pendingRow()));
    when(repository.restageIfPending(anyString(), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(0);

    storage.stage(content(), "application/pdf");

    // The reaper took the row and its object: a new row, and the upload, with no HEAD check.
    verify(repository).save(any(StorageObject.class));
    verify(provider, never()).exists(anyString());
    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }

  @Test
  @DisplayName("fresh content inserts the row and uploads, with no wasted HEAD check")
  void freshContentInsertsAndUploads() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.empty());

    storage.stage(content(), "application/pdf");

    verify(repository).save(any(StorageObject.class));
    verify(provider, never()).exists(anyString());
    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }

  @Test
  @DisplayName("a lost insert race (DataIntegrityViolation) still verify-and-(re)uploads")
  void concurrentInsertRaceIsVerifiedNotTrusted() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.empty());
    when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup key"));
    when(repository.restageIfPending(anyString(), any(), eq(StorageObjectStatus.PENDING)))
        .thenReturn(1);
    when(provider.exists(anyString())).thenReturn(false); // race winner's put had failed too

    storage.stage(content(), "application/pdf");

    verify(provider).exists(anyString());
    verify(provider, times(1)).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }
}
