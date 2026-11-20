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
package io.qnop.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.StorageObject;
import io.qnop.entity.StorageObjectStatus;
import io.qnop.observability.metrics.StorageMeters;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageRemediationService;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Object-storage acceptance against a real MinIO (issue #243, ADR-0005): put/get/delete round-trip,
 * content hashing, and the orphan reaper. Not {@code @Transactional} — object-store writes are not
 * rolled back, so each test uses unique content (content-addressed keys keep tests isolated) and
 * cleans up after itself.
 */
class StorageServiceIT extends AbstractIntegrationTest {

  @Autowired private StorageService storage;
  @Autowired private StorageObjectRepository repository;
  @Autowired private StorageProvider provider;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private StorageRemediationService remediation;

  private static byte[] uniqueContent() {
    return ("qnop-doc-" + UUID.randomUUID()).getBytes(UTF_8);
  }

  private double providerCalls(String operation) {
    var timer =
        meterRegistry
            .find(StorageMeters.OPERATION)
            .tag("operation", operation)
            .tag("outcome", "success")
            .timer();
    return timer == null ? 0 : timer.count();
  }

  private long rowVersion(String key) {
    return jdbc.queryForObject(
        "SELECT version FROM storage_object WHERE object_key = ?", Long.class, key);
  }

  private StagedObject stage(byte[] content, String contentType) {
    return storage.stage(new ByteArrayInputStream(content), contentType);
  }

  @Test
  void putGetDeleteRoundTrip() throws Exception {
    byte[] data = uniqueContent();

    StagedObject staged = stage(data, "application/pdf");
    assertThat(staged.key()).startsWith("sha256/");
    assertThat(staged.sizeBytes()).isEqualTo(data.length);

    try (StorageContent content = storage.get(staged.key()).orElseThrow()) {
      assertThat(content.stream().readAllBytes()).isEqualTo(data);
      assertThat(content.contentType()).isEqualTo("application/pdf");
      assertThat(content.contentLength()).isEqualTo(data.length);
    }

    storage.commit(staged.key());
    assertThat(storage.deleteUnlessReferenced(staged.key(), key -> false))
        .isEqualTo(StorageService.Deletion.DELETED);
    assertThat(storage.get(staged.key())).isEmpty();
    assertThat(repository.findByObjectKey(staged.key())).isEmpty();
  }

  @Test
  void keyIsContentAddressedBySha256() throws Exception {
    byte[] data = uniqueContent();
    String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));

    StagedObject staged = stage(data, "text/plain");

    assertThat(staged.contentHash()).isEqualTo(expected);
    assertThat(staged.key()).isEqualTo("sha256/" + expected.substring(0, 2) + "/" + expected);
    remove(staged.key());
  }

  @Test
  void reStagingHealsAPoisonedPendingRow() {
    // Reproduce the #289 poisoning: a PENDING registry row whose object is missing (a put that
    // failed after the row was persisted). Stage, then delete only the object — the row stays.
    byte[] data = uniqueContent();
    StagedObject staged = stage(data, "application/pdf");
    provider.delete(staged.key());
    assertThat(provider.exists(staged.key())).isFalse();
    assertThat(repository.findByObjectKey(staged.key()))
        .hasValueSatisfying(
            row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.PENDING));

    // Re-staging identical content must re-upload rather than trust the poisoned row.
    StagedObject restaged = stage(data, "application/pdf");

    assertThat(restaged.key()).isEqualTo(staged.key());
    assertThat(provider.exists(staged.key())).isTrue();
    remove(staged.key());
  }

  @Test
  void reStagingHealsACommittedRowWhoseObjectVanishedOutOfBand() {
    // Reproduce the #575 loss: a COMMITTED row (the object was durable once) whose object was
    // removed out of band — bucket wiped / restored without the object store. Delete only the
    // object via the provider; the registry row survives and stays COMMITTED.
    byte[] data = uniqueContent();
    StagedObject staged = stage(data, "application/pdf");
    storage.commit(staged.key());
    provider.delete(staged.key());
    assertThat(provider.exists(staged.key())).isFalse();
    assertThat(repository.findByObjectKey(staged.key()))
        .hasValueSatisfying(
            row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED));

    // Re-staging identical content must verify the vanished object and re-upload it, instead of
    // trusting the COMMITTED row and returning a key that points at nothing.
    StagedObject restaged = stage(data, "application/pdf");

    assertThat(restaged.key()).isEqualTo(staged.key());
    assertThat(provider.exists(staged.key())).isTrue();
    // Healing never demotes the row — it is still, correctly, COMMITTED.
    assertThat(repository.findByObjectKey(staged.key()))
        .hasValueSatisfying(
            row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED));
    remove(staged.key());
  }

  @Test
  void identicalContentDeduplicates() {
    byte[] data = uniqueContent();

    StagedObject first = stage(data, "application/pdf");
    StagedObject second = stage(data, "application/pdf");

    assertThat(second.key()).isEqualTo(first.key());
    assertThat(repository.findByObjectKey(first.key())).isPresent();
    remove(first.key());
  }

  @Test
  void deleteIsIdempotent() {
    StagedObject staged = stage(uniqueContent(), "application/pdf");
    storage.commit(staged.key());

    storage.deleteUnlessReferenced(staged.key(), key -> false);
    // A second delete must not fail.
    assertThat(storage.deleteUnlessReferenced(staged.key(), key -> false))
        .isEqualTo(StorageService.Deletion.DELETED);

    assertThat(storage.get(staged.key())).isEmpty();
  }

  @Test
  void getMissingKeyReturnsEmpty() {
    // A well-formed content-addressed key (issue #337) for content that was never staged: the
    // all-zero SHA-256, so the shard matches its prefix and the read is a clean miss, not a reject.
    assertThat(storage.get("sha256/00/" + "0".repeat(64))).isEmpty();
  }

  @Test
  void reaperDeletesUncommittedOrphanButKeepsCommitted() {
    StagedObject orphan = stage(uniqueContent(), "application/pdf");
    StagedObject committed = stage(uniqueContent(), "application/pdf");
    storage.commit(committed.key());

    // Backdate both rows beyond the reaper grace period (default 1h).
    jdbc.update(
        "UPDATE storage_object SET created_at = created_at - interval '2 hours' "
            + "WHERE object_key IN (?, ?)",
        orphan.key(),
        committed.key());
    double reapedBefore = meterRegistry.counter(StorageMeters.ORPHANS_REAPED).count();
    double deletesBefore = providerCalls("delete");

    storage.reapOrphans();

    // Other tests' leftovers may be reaped in the same pass, so: at least this one (issue #962).
    assertThat(meterRegistry.counter(StorageMeters.ORPHANS_REAPED).count())
        .isGreaterThanOrEqualTo(reapedBefore + 1);
    // The service measures the provider it was given, whichever implementation that is.
    assertThat(providerCalls("delete")).isGreaterThanOrEqualTo(deletesBefore + 1);

    assertThat(repository.findByObjectKey(orphan.key())).isEmpty();
    assertThat(storage.get(orphan.key())).isEmpty();
    assertThat(repository.findByObjectKey(committed.key())).isPresent();
    assertThat(storage.get(committed.key())).isPresent();

    remove(committed.key());
  }

  @Test
  void reStagingAnOldPendingRowKeepsItFromTheReaper() {
    // An upload whose content matches a PENDING row older than the grace period reuses that row.
    // Before #987 the row kept its old created_at, so the reaper could take it, and the object,
    // between this upload's stage and its commit.
    byte[] data = uniqueContent();
    StagedObject first = stage(data, "application/pdf");
    backdate(first.key());

    StagedObject again = stage(data, "application/pdf");
    storage.reapOrphansOnce(false);

    assertThat(again.key()).isEqualTo(first.key());
    assertThat(repository.findByObjectKey(again.key())).isPresent();
    assertThat(provider.exists(again.key())).isTrue();
    storage.commit(again.key());
    assertThat(repository.findByObjectKey(again.key()))
        .hasValueSatisfying(
            row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED));
    remove(again.key());
  }

  @Test
  void reaperRacingACommitNeverLeavesACommittedRowWithoutItsObject() throws Exception {
    // The reaper and a commit race on the same old PENDING row (issue #987). Before, the reaper
    // deleted the object first and the row only when its transaction ended; a commit in between
    // made the row delete fail and roll back, leaving a COMMITTED row whose object was gone.
    // Either side may win now, but the row and its object go together. Several rounds, because
    // one round may not interleave.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 10; round++) {
        StagedObject staged = stage(uniqueContent(), "application/pdf");
        backdate(staged.key());
        CyclicBarrier start = new CyclicBarrier(2);
        Future<?> reaper =
            pool.submit(
                () -> {
                  start.await();
                  return storage.reapOrphansOnce(false);
                });
        Future<?> commit =
            pool.submit(
                () -> {
                  start.await();
                  storage.commit(staged.key());
                  return null;
                });
        reaper.get(30, TimeUnit.SECONDS);
        commit.get(30, TimeUnit.SECONDS);

        Optional<StorageObject> row = repository.findByObjectKey(staged.key());
        if (row.isPresent()) {
          assertThat(row.get().getStatus()).isEqualTo(StorageObjectStatus.COMMITTED);
          assertThat(provider.exists(staged.key())).as("object of a committed row").isTrue();
          remove(staged.key());
        } else {
          assertThat(provider.exists(staged.key())).as("object of a reaped row").isFalse();
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void reaperWaitingOnACommitKeepsTheCommittedRowAndItsObject() throws Exception {
    // Deterministic form of the race above (issue #987): the commit holds the row lock when the
    // reaper reaches the row. The reaper must wait, see the row committed, and keep the object.
    StagedObject staged = stage(uniqueContent(), "application/pdf");
    backdate(staged.key());

    reapWhileHolding(() -> storage.commit(staged.key()));

    assertThat(repository.findByObjectKey(staged.key()))
        .hasValueSatisfying(
            row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED));
    assertThat(provider.exists(staged.key())).isTrue();
    remove(staged.key());
  }

  @Test
  void reaperWaitingOnARestageKeepsTheRowAndItsObject() throws Exception {
    // A stage of the same content holds the row lock while it marks the old row as staged now
    // (issue #987). The reaper read the row as old; it must wait, see it fresh, and skip it.
    byte[] data = uniqueContent();
    StagedObject staged = stage(data, "application/pdf");
    backdate(staged.key());

    reapWhileHolding(
        () ->
            repository.restageIfPending(staged.key(), Instant.now(), StorageObjectStatus.PENDING));

    assertThat(repository.findByObjectKey(staged.key()))
        .hasValueSatisfying(
            row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.PENDING));
    assertThat(provider.exists(staged.key())).isTrue();
    remove(staged.key());
  }

  /**
   * Runs {@code holder} in a transaction, starts a reaper pass, and commits the transaction only
   * once the reaper is waiting for a lock that {@code holder} took; then waits for the pass.
   */
  private void reapWhileHolding(Runnable holder) throws Exception {
    runWhileHolding(holder, () -> storage.reapOrphansOnce(false));
  }

  /**
   * Runs {@code holder} in a transaction, starts {@code contender} on another thread, and commits
   * the transaction only once the contender is waiting for a lock; then returns its result.
   */
  private <T> T runWhileHolding(Runnable holder, Callable<T> contender) throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<T> result =
          new TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    holder.run();
                    return startWaiting(pool, contender);
                  });
      return result.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  /** Starts {@code task} on {@code pool} and returns once it is waiting for a lock. */
  private <T> Future<T> startWaiting(ExecutorService pool, Callable<T> task) {
    Future<T> result = pool.submit(task);
    awaitLockWait(result);
    return result;
  }

  /** Waits until a session of this database waits for a lock, at most ten seconds. */
  private void awaitLockWait(Future<?> contender) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (contender.isDone()) {
        try {
          throw new AssertionError("the contender finished without waiting: " + contender.get());
        } catch (InterruptedException | ExecutionException e) {
          throw new AssertionError("the contender failed without waiting", e);
        }
      }
      // Usually called inside the holder's transaction, where PostgreSQL would otherwise keep
      // serving the activity snapshot taken at its first read.
      jdbc.execute("SELECT pg_stat_clear_snapshot()");
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE wait_event_type = 'Lock' AND datname = current_database()"
                  // Only the storage registry's waits, not those of unrelated background work.
                  + " AND (query ILIKE '%storage_object%' OR query ILIKE '%pg_advisory%')",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("the contender never waited for a lock");
  }

  /** Test cleanup: removes the object and its row, whatever state they are in. */
  private void remove(String key) {
    provider.delete(key);
    jdbc.update("DELETE FROM storage_object WHERE object_key = ?", key);
  }

  // --- deleting content an upload is reusing (issue #992) ---------------------

  /** Stages and commits unique content, as a finished upload leaves it. */
  private StagedObject stored(byte[] data) {
    StagedObject staged = stage(data, "application/pdf");
    storage.commit(staged.key());
    return staged;
  }

  private StorageObjectStatus status(String key) {
    return repository.findByObjectKey(key).map(StorageObject::getStatus).orElse(null);
  }

  @Test
  void deletingContentAnUploadStagedAgainLeavesItToThatUploadsCommit() {
    // Another upload of the same content dedups onto the stored object, and the last document
    // using the content is deleted before that upload's own version is written. Before #992 the
    // object went, and the new version pointed at nothing.
    byte[] data = uniqueContent();
    StagedObject first = stored(data);
    StagedObject again = stage(data, "application/pdf");

    StorageService.Deletion outcome = storage.deleteUnlessReferenced(first.key(), key -> false);

    assertThat(outcome).isEqualTo(StorageService.Deletion.STAGED);
    assertThat(provider.exists(again.key())).isTrue();
    assertThat(status(again.key())).isEqualTo(StorageObjectStatus.PENDING);
    storage.commit(again.key());
    assertThat(status(again.key())).isEqualTo(StorageObjectStatus.COMMITTED);
    remove(again.key());
  }

  @Test
  void contentHandedBackToTheReaperGoesIfTheUploadNeverCommits() {
    byte[] data = uniqueContent();
    StagedObject first = stored(data);
    stage(data, "application/pdf"); // an upload that fails before its commit
    storage.deleteUnlessReferenced(first.key(), key -> false);

    // Pending since that upload staged it; once the grace period has passed, the reaper takes it.
    jdbc.update(
        "UPDATE storage_object SET created_at = created_at - interval '2 hours',"
            + " last_staged_at = last_staged_at - interval '2 hours' WHERE object_key = ?",
        first.key());
    storage.reapOrphansOnce(false);

    assertThat(repository.findByObjectKey(first.key())).isEmpty();
    assertThat(provider.exists(first.key())).isFalse();
  }

  @Test
  void contentNoUploadStagedAgainIsDeleted() {
    StagedObject first = stored(uniqueContent());

    assertThat(storage.deleteUnlessReferenced(first.key(), key -> false))
        .isEqualTo(StorageService.Deletion.DELETED);

    assertThat(repository.findByObjectKey(first.key())).isEmpty();
    assertThat(provider.exists(first.key())).isFalse();
  }

  @Test
  void deletionWaitingOnAStageOfTheSameContentKeepsTheObject() throws Exception {
    // The stage holds the row lock while it marks the committed row as staged. The deletion must
    // wait for it and see the mark, rather than judge the row from before it.
    byte[] data = uniqueContent();
    StagedObject first = stored(data);

    StorageService.Deletion outcome =
        runWhileHolding(
            () ->
                repository.markStagedIfCommitted(
                    first.key(), Instant.now(), StorageObjectStatus.COMMITTED),
            () -> storage.deleteUnlessReferenced(first.key(), key -> false));

    assertThat(outcome).isEqualTo(StorageService.Deletion.STAGED);
    assertThat(provider.exists(first.key())).isTrue();
    remove(first.key());
  }

  @Test
  void commitWaitingOnADeletionCommitsTheRowItHandsBack() throws Exception {
    // The upload's commit arrives while the deletion holds the row. Unless the commit waits for
    // the lock, it judges the row COMMITTED from before the hand-back and leaves it PENDING, for
    // the reaper to take although the upload's version now references it.
    byte[] data = uniqueContent();
    StagedObject first = stored(data);
    stage(data, "application/pdf");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      List<Future<?>> commit = new ArrayList<>();
      StorageService.Deletion outcome =
          storage.deleteUnlessReferenced(
              first.key(),
              key -> {
                commit.add(
                    startWaiting(
                        pool,
                        () -> {
                          storage.commit(key);
                          return null;
                        }));
                return false;
              });
      commit.getFirst().get(30, TimeUnit.SECONDS);

      assertThat(outcome).isEqualTo(StorageService.Deletion.STAGED);
      assertThat(status(first.key())).isEqualTo(StorageObjectStatus.COMMITTED);
      assertThat(provider.exists(first.key())).isTrue();
    } finally {
      pool.shutdownNow();
    }
    remove(first.key());
  }

  @Test
  void stageWaitingOnADeletionUploadsTheContentAgain() throws Exception {
    // The deletion holds the row when an upload of the same content arrives. The stage must wait,
    // find the row gone, and register and upload the content anew.
    byte[] data = uniqueContent();
    StagedObject first = stored(data);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      List<Future<StagedObject>> upload = new ArrayList<>();
      StorageService.Deletion outcome =
          storage.deleteUnlessReferenced(
              first.key(),
              key -> {
                upload.add(startWaiting(pool, () -> stage(data, "application/pdf")));
                return false;
              });
      StagedObject again = upload.getFirst().get(30, TimeUnit.SECONDS);

      assertThat(outcome).isEqualTo(StorageService.Deletion.DELETED);
      assertThat(again.key()).isEqualTo(first.key());
      assertThat(status(again.key())).isEqualTo(StorageObjectStatus.PENDING);
      assertThat(provider.exists(again.key())).isTrue();
    } finally {
      pool.shutdownNow();
    }
    remove(first.key());
  }

  @Test
  void stageOfAKeyWithoutARowWaitsForItsDeletion() throws Exception {
    // A key with an object but no row, as a bucket orphan is. A row lock cannot guard it, so the
    // stage's insert must wait on the key's lock; otherwise it puts the object, and the deletion
    // that is under way removes it from under the new row.
    byte[] data = uniqueContent();
    StagedObject first = stage(data, "application/pdf");
    jdbc.update("DELETE FROM storage_object WHERE object_key = ?", first.key());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      List<Future<StagedObject>> upload = new ArrayList<>();
      StorageService.Deletion outcome =
          storage.deleteUnlessReferenced(
              first.key(),
              key -> {
                upload.add(startWaiting(pool, () -> stage(data, "application/pdf")));
                return false;
              });
      upload.getFirst().get(30, TimeUnit.SECONDS);

      assertThat(outcome).isEqualTo(StorageService.Deletion.DELETED);
      assertThat(status(first.key())).isEqualTo(StorageObjectStatus.PENDING);
      assertThat(provider.exists(first.key())).isTrue();
    } finally {
      pool.shutdownNow();
    }
    remove(first.key());
  }

  @Test
  void remediationWaitsForAStageRegisteringTheSameContent() throws Exception {
    // A bucket orphan is remediated while an upload of the same content registers its row. The
    // remediation must wait for the key, then see the row and keep the object. The holder is the
    // stage's insert transaction (lock the key, insert the row), which a real stage commits too
    // quickly to hold open.
    byte[] data = uniqueContent();
    StagedObject first = stage(data, "application/pdf");
    jdbc.update("DELETE FROM storage_object WHERE object_key = ?", first.key());

    StorageRemediationService.RemediationResult result =
        runWhileHolding(
            () -> {
              repository.lockObjectKey(first.key());
              repository.saveAndFlush(
                  StorageObject.pending(
                      first.key(), first.contentHash(), "application/pdf", first.sizeBytes()));
            },
            () -> remediation.deleteOrphans(List.of(first.key()), null));

    assertThat(result.deleted()).isEmpty();
    assertThat(result.skipped()).hasSize(1);
    assertThat(provider.exists(first.key())).isTrue();
    remove(first.key());
  }

  /** Moves a row's staging time beyond the reaper's grace period (default 1h). */
  private void backdate(String key) {
    jdbc.update(
        "UPDATE storage_object SET created_at = created_at - interval '2 hours'"
            + " WHERE object_key = ?",
        key);
  }

  @Test
  void recentUncommittedObjectSurvivesTheReaper() {
    StagedObject fresh = stage(uniqueContent(), "application/pdf");

    storage.reapOrphans(); // within the grace period → not reaped

    assertThat(repository.findByObjectKey(fresh.key())).isPresent();
    assertThat(storage.get(fresh.key())).isPresent();
    remove(fresh.key());
  }

  @Test
  void concurrentCommitsOfOneKeyAllSucceed() throws Exception {
    // Identical uploads share one content-addressed row (ADR-0036); their commits race on it.
    // Before #982 the loser failed the optimistic-lock check, and the upload answered 500 although
    // its document had been created. Several rounds, because one round may not interleave.
    int writers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      for (int round = 0; round < 5; round++) {
        StagedObject staged = stage(uniqueContent(), "application/pdf");
        CyclicBarrier start = new CyclicBarrier(writers);
        List<Future<?>> commits = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
          commits.add(
              pool.submit(
                  () -> {
                    start.await();
                    storage.commit(staged.key());
                    return null;
                  }));
        }
        for (Future<?> commit : commits) {
          commit.get(30, TimeUnit.SECONDS);
        }

        assertThat(repository.findByObjectKey(staged.key()))
            .hasValueSatisfying(
                row -> assertThat(row.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED));
        remove(staged.key());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void committingACommittedObjectLeavesItUntouched() {
    StagedObject staged = stage(uniqueContent(), "application/pdf");
    storage.commit(staged.key());
    StorageObject first = repository.findByObjectKey(staged.key()).orElseThrow();
    long firstVersion = rowVersion(staged.key());

    storage.commit(staged.key());

    StorageObject again = repository.findByObjectKey(staged.key()).orElseThrow();
    assertThat(again.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED);
    assertThat(again.getCommittedAt()).isEqualTo(first.getCommittedAt());
    assertThat(rowVersion(staged.key())).isEqualTo(firstVersion);
    remove(staged.key());
  }

  @Test
  void committingAnUnknownKeyIsANoOp() {
    String unknown = "sha256/00/" + "0".repeat(64);

    storage.commit(unknown);

    assertThat(repository.findByObjectKey(unknown)).isEmpty();
  }
}
