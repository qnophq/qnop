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

import static io.qnop.entity.StorageObjectStatus.PENDING;

import io.qnop.entity.StorageObject;
import io.qnop.entity.StorageObjectStatus;
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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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
    this.tx = new TransactionTemplate(transactionManager);
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
   */
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
  public StagedObject stage(InputStream content, String contentType, long maxBytes) {
    Path buffer = bufferToTempFile(content, maxBytes);
    try {
      String hash = sha256(buffer);
      long size = sizeOf(buffer);
      String key = keyFor(hash);

      Optional<StorageObject> existing = repository.findByObjectKey(key);
      if (existing.map(o -> o.getStatus() == StorageObjectStatus.COMMITTED).orElse(false)) {
        // Fast path: a COMMITTED row means the object was durable once. But it can still vanish
        // out of band (bucket wiped, environment restored without the object store), and the row
        // survives — so verify it, and re-materialize from the bytes we already buffered when it
        // is gone, rather than returning a key that points at nothing (issue #575). One HEAD per
        // dedup hit is negligible against an upload; the row stays COMMITTED (it still is).
        if (!provider.exists(key)) {
          log.warn(
              "Committed storage object {} was missing from the provider — re-uploaded from "
                  + "buffered content (out-of-band object loss)",
              key);
          uploadBuffered(buffer, key, size, contentType);
        }
        return new StagedObject(key, hash, size);
      }
      // Persist the PENDING row (in Spring Data's own transaction) BEFORE the upload, so a crash
      // between the two always leaves a row the reaper can reclaim. A concurrent stage of identical
      // content may win the unique-key race — its row is only PENDING (the object may be missing),
      // so we still verify-and-(re)upload below rather than trusting it.
      boolean inserted = existing.isEmpty() && insertPending(key, hash, contentType, size);
      // A reused row may be older than the reaper's grace period, so it is marked as staged now:
      // otherwise the reaper could take it, and its object, between this stage and the commit
      // (issue #987). If the row is no longer PENDING, the reaper took it after it was read here
      // (its object is gone with it) or an upload committed it; the insert tells the two apart.
      if (!inserted && repository.restageIfPending(key, Instant.now(), PENDING) == 0) {
        inserted = insertPending(key, hash, contentType, size);
      }
      // A freshly inserted row always needs the upload; a pre-existing row (possibly poisoned,
      // mid-flight, or a race winner's) needs it only when the object is actually absent.
      if (inserted || !provider.exists(key)) {
        uploadBuffered(buffer, key, size, contentType);
      }
      return new StagedObject(key, hash, size);
    } finally {
      deleteQuietly(buffer);
    }
  }

  /**
   * Inserts the {@code PENDING} row of a key, in Spring Data's own transaction.
   *
   * @return true if this call inserted it, false if a row for the key exists already
   */
  private boolean insertPending(String key, String hash, String contentType, long size) {
    try {
      repository.save(StorageObject.pending(key, hash, contentType, size));
      return true;
    } catch (DataIntegrityViolationException e) {
      return false;
    }
  }

  private void uploadBuffered(Path buffer, String key, long size, String contentType) {
    try (InputStream in = Files.newInputStream(buffer)) {
      provider.put(key, in, size, contentType);
    } catch (IOException e) {
      throw new StorageException("Failed to read buffered upload for " + key, e);
    }
  }

  /**
   * Marks the object committed (its domain row is now durable). Idempotent and safe under
   * concurrency: identical uploads committing the same content-addressed row at once all succeed,
   * and an already committed row is left as it is (issue #982).
   *
   * <p>The update is a bulk statement that bypasses the persistence context: a {@code
   * StorageObject} the caller already holds is not refreshed by it. A key without a row is not an
   * error, but it means the row vanished between stage and commit (the orphan reaper took it), so
   * the domain row now references an object the registry no longer tracks; that is logged.
   */
  @Transactional
  public void commit(String key) {
    int committed =
        repository.commitIfPending(
            key, Instant.now(), StorageObjectStatus.PENDING, StorageObjectStatus.COMMITTED);
    if (committed == 0 && repository.findByObjectKey(key).isEmpty()) {
      log.warn(
          "Commit of storage object {} found no registry row; it was removed between stage and"
              + " commit",
          key);
    }
  }

  /** Opens the object for reading, or empty if absent. The caller closes the returned content. */
  public Optional<StorageContent> get(String key) {
    return provider.get(key);
  }

  /** Removes the object and its registry row. Idempotent. */
  @Transactional
  public void delete(String key) {
    provider.delete(key);
    repository.findByObjectKey(key).ifPresent(repository::delete);
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
