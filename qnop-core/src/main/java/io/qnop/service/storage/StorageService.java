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

import static io.qnop.entity.StorageObjectStatus.COMMITTED;
import static io.qnop.entity.StorageObjectStatus.PENDING;

import io.qnop.entity.StorageObject;
import io.qnop.observability.metrics.StorageMeters;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upload-then-commit consistency layer over the {@link StorageProvider} (issue #243,
 * ADR-0005/0036).
 *
 * <p>{@link #stage} durably records a {@code PENDING} registry row <em>before</em> the object is
 * put to storage, so a crash between the two never leaves an object the reaper cannot find. The
 * caller persists its domain row (referencing the returned key) and then calls {@link #commit}; the
 * {@link #reapOrphans() reaper} deletes objects left {@code PENDING} past the grace period. Keys
 * are content-addressed (sha-256), so identical content deduplicates to one object and row.
 */
@Service
public class StorageService {

  private static final Logger log = LoggerFactory.getLogger(StorageService.class);

  /**
   * How often a stage looks for the key's row again when it changed under it. Each retry follows a
   * concurrent insert, deletion or reap of the same content, so more than a couple is unheard of.
   */
  private static final int MAX_STAGE_ATTEMPTS = 5;

  private final StorageProvider provider;
  private final StorageObjectRepository repository;
  private final S3Properties properties;
  private final SchedulerService scheduler;
  private final StorageMeters meters;
  private final TransactionTemplate tx;

  public StorageService(
      StorageProvider provider,
      StorageObjectRepository repository,
      S3Properties properties,
      SchedulerService scheduler,
      StorageMeters meters,
      PlatformTransactionManager transactionManager) {
    this.provider = MeteredStorageProvider.around(provider, meters);
    this.repository = repository;
    this.properties = properties;
    this.scheduler = scheduler;
    this.meters = meters;
    // Reaping an orphan, deleting a key and inserting a row each need a transaction of their own,
    // even inside a caller's: joining it would defer the claim or lock to its end, and a rollback
    // there would bring back a row whose object is gone (issues #987, #992).
    this.tx = new TransactionTemplate(transactionManager);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Buffers the content (to compute its hash and length), records a {@code PENDING} registry row,
   * and uploads it under a content-addressed key.
   *
   * <p><strong>A registry hit is a dedup hint, never blind trust</strong> — the object's real
   * existence is verified before the upload is skipped, and it is (re-)uploaded from the buffered
   * bytes whenever it is actually absent. {@code put} is idempotent for a content-addressed key +
   * identical bytes, so this self-heals without ever returning a key that points at nothing. Two
   * ways the row can outlive its object: a {@code PENDING} row can be <em>poisoned</em> — it is
   * persisted before {@code put}, so a failed upload leaves a row with no object (issue #289); and
   * a {@code COMMITTED} row can be orphaned by <em>out-of-band</em> object loss — the bucket is
   * wiped or an environment is restored without the object store, leaving the row pointing at
   * nothing (issue #575). Both are healed here because {@code stage} demonstrably holds the full
   * bytes.
   *
   * <p>Never called inside a transaction (issue #992): each registry step commits on its own, so no
   * row lock is held while the insert waits for the key's lock that a deletion holds.
   */
  @Transactional(propagation = Propagation.NEVER)
  public StagedObject stage(InputStream content, String contentType) {
    return stage(content, contentType, Long.MAX_VALUE);
  }

  /**
   * As {@link #stage(InputStream, String)}, but aborts with {@link StorageQuotaExceededException}
   * once the stream exceeds {@code maxBytes} — before the whole stream is buffered and before any
   * upload (issue #361), so an over-limit upload never touches the backend and never fully lands on
   * local disk. This is the authoritative size check for content whose declared length cannot be
   * trusted.
   */
  @Transactional(propagation = Propagation.NEVER)
  public StagedObject stage(InputStream content, String contentType, long maxBytes) {
    Path buffer = bufferToTempFile(content, maxBytes);
    try {
      String hash = sha256(buffer);
      long size = sizeOf(buffer);
      String key = keyFor(hash);

      DataIntegrityViolationException conflict = null;
      for (int attempt = 1; ; attempt++) {
        Instant now = Instant.now();
        // Content that is stored and committed already is reused, and marked as staged now:
        // nothing references it on this upload's behalf until its domain row is written, so a
        // deletion in between must see that the key is in use (issue #992).
        if (repository.markStagedIfCommitted(key, now, COMMITTED) == 1) {
          // A COMMITTED row means the object was durable once. But it can still vanish out of
          // band (bucket wiped, environment restored without the object store), and the row
          // survives — so verify it, and re-materialize from the bytes we already buffered when
          // it is gone, rather than returning a key that points at nothing (issue #575). One HEAD
          // per dedup hit is negligible against an upload; the row stays COMMITTED (it still is).
          if (!provider.exists(key)) {
            log.warn(
                "Committed storage object {} was missing from the provider — re-uploaded from "
                    + "buffered content (out-of-band object loss)",
                key);
            uploadBuffered(buffer, key, size, contentType);
          }
          break;
        }
        // A PENDING row is another upload's, possibly a failed one's (poisoned, issue #289). It
        // may be older than the reaper's grace period, so it is marked as staged now: otherwise
        // the reaper could take it, and its object, between this stage and the commit (issue
        // #987). The object is uploaded only if it is actually absent.
        if (repository.restageIfPending(key, now, PENDING) == 1) {
          if (!provider.exists(key)) {
            uploadBuffered(buffer, key, size, contentType);
          }
          break;
        }
        // No row: persist the PENDING row BEFORE the upload, so a crash between the two always
        // leaves a row the reaper can reclaim.
        try {
          insertPending(key, hash, contentType, size);
          uploadBuffered(buffer, key, size, contentType);
          break;
        } catch (DataIntegrityViolationException e) {
          // Most likely a concurrent stage inserted the row after it was looked for here, or a
          // deletion or the reaper removed it in between: look again.
          conflict = e;
        }
        if (attempt == MAX_STAGE_ATTEMPTS) {
          throw new StorageException(
              "Could not register storage object " + key + ": its registry row kept changing",
              conflict);
        }
      }
      return new StagedObject(key, hash, size);
    } finally {
      deleteQuietly(buffer);
    }
  }

  /**
   * Inserts the {@code PENDING} row of a key, in a transaction of its own.
   *
   * @throws DataIntegrityViolationException if the insert violates a constraint, most likely
   *     because a row for the key exists already
   */
  private void insertPending(String key, String hash, String contentType, long size) {
    tx.executeWithoutResult(
        status -> {
          // Waits for a deletion of the same key that is under way (issue #992): its object
          // must not be deleted after this upload has put it.
          repository.lockObjectKey(key);
          repository.saveAndFlush(StorageObject.pending(key, hash, contentType, size));
        });
  }

  /**
   * Stores the buffered upload. The provider may open the buffer once per attempt, so a retry after
   * a dropped connection sends the whole content again (issue #983).
   */
  private void uploadBuffered(Path buffer, String key, long size, String contentType) {
    provider.putReopenable(key, () -> Files.newInputStream(buffer), size, contentType);
  }

  /**
   * Marks the object committed (its domain row is now durable). Idempotent and safe under
   * concurrency: identical uploads committing the same content-addressed row at once all succeed,
   * and an already committed row is left as it is (issue #982).
   *
   * <p>The update is a bulk statement that bypasses the persistence context: a {@code
   * StorageObject} the caller already holds is not refreshed by it. A key without a row is not an
   * error, but it means the row vanished between stage and commit (the orphan reaper, or a
   * deletion, took it after the grace period), so the domain row now references an object the
   * registry no longer tracks; that is logged.
   */
  @Transactional
  public void commit(String key) {
    // Locked first, so that a deletion handing the row back to the reaper is waited for and its
    // PENDING row is committed, rather than judged COMMITTED from before it (issue #992).
    if (repository.findByObjectKeyForUpdate(key).isEmpty()) {
      log.warn(
          "Commit of storage object {} found no registry row; it was removed between stage and"
              + " commit",
          key);
      return;
    }
    repository.commitIfPending(key, Instant.now(), PENDING, COMMITTED);
  }

  /** Opens the object for reading, or empty if absent. The caller closes the returned content. */
  public Optional<StorageContent> get(String key) {
    return provider.get(key);
  }

  /** What {@link #deleteUnlessReferenced} did with a key. */
  public enum Deletion {
    /** The object and its registry row, if it had one, are gone. */
    DELETED,
    /** Something references the key, so both were kept. */
    REFERENCED,
    /**
     * An upload staged the key within the reaper's grace period and may still commit it. Both were
     * kept, and the row is left {@code PENDING} for that commit or else for the reaper. The mark is
     * not cleared by that commit, as other uploads of the same content may still be in flight, so
     * content uploaded again shortly before its deletion is only removed by a later reaper run.
     */
    STAGED
  }

  /**
   * Deletes the object of {@code key} and its registry row, unless the key is referenced or an
   * upload may be about to reference it (issue #992). Idempotent.
   *
   * <p>Keys are content-addressed, so an upload of identical content reuses the object without
   * uploading it, and references it only once its own domain row is written. The deletion therefore
   * runs in a transaction of its own that first locks the key: a stage that would insert the key's
   * row, and a stage or commit of its existing row, wait for it. Only then is {@code isReferenced}
   * asked, so a reference written since the caller last looked keeps the object. A row an upload
   * staged within the grace period is handed back to the reaper as {@code PENDING} instead: the
   * upload's commit makes it {@code COMMITTED} again, and an upload that never commits leaves it to
   * the reaper. A {@code PENDING} row is the reaper's already. Its own transaction also means the
   * object is never deleted while the row could still come back with a caller's rollback.
   *
   * @param isReferenced whether anything references the key; called inside the deletion's
   *     transaction, after the key is locked
   */
  public Deletion deleteUnlessReferenced(String key, Predicate<String> isReferenced) {
    Instant stagedSince = Instant.now().minus(properties.reaperGracePeriod());
    return tx.execute(status -> deleteLocked(key, isReferenced, stagedSince));
  }

  private Deletion deleteLocked(String key, Predicate<String> isReferenced, Instant stagedSince) {
    repository.lockObjectKey(key);
    Optional<StorageObject> row = repository.findByObjectKeyForUpdate(key);
    if (isReferenced.test(key)) {
      return Deletion.REFERENCED;
    }
    if (row.isPresent()) {
      StorageObject object = row.get();
      if (object.getStatus() == PENDING) {
        return Deletion.STAGED;
      }
      Instant lastStaged = object.getLastStagedAt();
      if (lastStaged != null && lastStaged.isAfter(stagedSince)) {
        repository.handBackToReaper(object.getId(), PENDING);
        log.info(
            "Kept storage object {}: an upload staged it at {} and may still commit it; the"
                + " reaper removes it if it does not",
            key,
            lastStaged);
        return Deletion.STAGED;
      }
      // Removed before the object, so a failure here leaves both.
      repository.delete(object);
      repository.flush();
    }
    // Under both locks, as the reaper does: a stage or commit of this one key waits for a single
    // object delete, and none can put the object again before it is gone.
    provider.delete(key);
    return Deletion.DELETED;
  }

  /**
   * Deletes objects left uploaded-but-uncommitted past the grace period (ADR-0036). Runs off-peak,
   * single-instance via ShedLock (ADR-0029). Routed through the scheduler gate (issue #524) so an
   * admin can disable it, put it in dry-run, or trigger a run-now. The job is self-transactional
   * (issue #987): {@link #reapOrphansOnce(boolean)} opens one transaction per orphan.
   */
  @Scheduled(cron = "${qnop.s3.reaper-cron:0 30 3 * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.STORAGE_ORPHAN_REAPER, lockAtMostFor = "PT10M")
  public void reapOrphans() {
    scheduler.runScheduled(SchedulerJobCatalog.STORAGE_ORPHAN_REAPER);
  }

  /**
   * The raw reaper pass, run by the scheduler gate without an enclosing transaction (issues #524,
   * #987). In {@code dryRun} mode it counts what would be deleted but deletes nothing — a safe
   * operator probe.
   *
   * <p>Each orphan is reaped in a transaction of its own, which claims the row first and deletes
   * the object only if the claim succeeded. An upload that committed the row, or staged the same
   * content again, after the orphans were read keeps both. A commit that arrives while the claim
   * holds the row lock waits for it and then finds no row, which {@link #commit} logs. The object
   * is deleted while the row lock is held, so a stage of the same content cannot upload it in
   * between. One orphan that fails does not stop the others; the run then fails as a whole.
   */
  public String reapOrphansOnce(boolean dryRun) {
    Instant cutoff = Instant.now().minus(properties.reaperGracePeriod());
    List<StorageObject> candidates = repository.findByStatusAndCreatedAtBefore(PENDING, cutoff);
    if (candidates.isEmpty()) {
      return "No orphaned uploads to reap";
    }
    if (dryRun) {
      log.info(
          "Storage orphan reaper (dry-run) would delete {} uncommitted object(s).",
          candidates.size());
      return "Would delete " + candidates.size() + " uncommitted object(s); nothing was changed";
    }
    int reaped = 0;
    int failed = 0;
    for (StorageObject candidate : candidates) {
      try {
        if (Boolean.TRUE.equals(tx.execute(status -> reap(candidate, cutoff)))) {
          reaped++;
        }
      } catch (RuntimeException e) {
        // The row's claim rolled back with its transaction, so the orphan is tried again next run.
        failed++;
        log.warn("Storage orphan reaper could not delete {}", candidate.getObjectKey(), e);
      }
    }
    meters.orphansReaped(reaped);
    int skipped = candidates.size() - reaped - failed;
    log.info(
        "Storage orphan reaper deleted {} uncommitted object(s); {} were committed or staged"
            + " again meanwhile, {} failed.",
        reaped,
        skipped,
        failed);
    if (failed > 0) {
      throw new StorageException(
          "Deleted " + reaped + " uncommitted object(s), but " + failed + " could not be deleted");
    }
    return "Deleted " + reaped + " uncommitted object(s)";
  }

  /**
   * Claims one orphan and deletes its object, inside the caller's transaction.
   *
   * @return true if the orphan was removed, false if it is no longer one
   */
  private boolean reap(StorageObject candidate, Instant cutoff) {
    if (repository.deleteIfOrphaned(candidate.getId(), cutoff, PENDING) == 0) {
      return false;
    }
    provider.delete(candidate.getObjectKey());
    return true;
  }

  /**
   * Content-addressed key: {@code sha256/<first-2-hex>/<full-hex>} (sharded to avoid a flat set).
   */
  private static String keyFor(String hash) {
    return "sha256/" + hash.substring(0, 2) + "/" + hash;
  }

  private static Path bufferToTempFile(InputStream content, long maxBytes) {
    Path temp;
    try {
      temp = Files.createTempFile("qnop-storage-", ".tmp");
    } catch (IOException e) {
      throw new StorageException("Failed to buffer upload", e);
    }
    try (OutputStream out = Files.newOutputStream(temp)) {
      copyBounded(content, out, maxBytes);
      return temp;
    } catch (StorageQuotaExceededException e) {
      deleteQuietly(temp); // never keep an over-limit partial on disk
      throw e;
    } catch (IOException e) {
      deleteQuietly(temp);
      throw new StorageException("Failed to buffer upload", e);
    }
  }

  /**
   * Copies at most {@code maxBytes}; if the stream still has data at that point the content is
   * over-limit and {@link StorageQuotaExceededException} is thrown before more is read (issue
   * #361).
   */
  private static void copyBounded(InputStream in, OutputStream out, long maxBytes)
      throws IOException {
    byte[] buffer = new byte[8192];
    long remaining = maxBytes;
    int read;
    while ((read = in.read(buffer)) != -1) {
      if (read > remaining) {
        out.write(buffer, 0, (int) remaining); // fill exactly to the limit, then reject
        throw new StorageQuotaExceededException(maxBytes);
      }
      out.write(buffer, 0, read);
      remaining -= read;
    }
  }

  private static String sha256(Path path) {
    try (InputStream in = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new StorageException("Failed to hash upload", e);
    }
  }

  private static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new StorageException("Failed to size upload", e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete temp upload buffer {}: {}", path, e.getMessage());
    }
  }
}
