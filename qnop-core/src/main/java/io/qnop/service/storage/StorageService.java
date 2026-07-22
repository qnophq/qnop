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

import io.qnop.entity.StorageObject;
import io.qnop.entity.StorageObjectStatus;
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
import org.springframework.transaction.annotation.Transactional;

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

  public StorageService(
      StorageProvider provider,
      StorageObjectRepository repository,
      S3Properties properties,
      SchedulerService scheduler) {
    this.provider = provider;
    this.repository = repository;
    this.properties = properties;
    this.scheduler = scheduler;
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
      boolean rowPreexisted = existing.isPresent();
      if (!rowPreexisted) {
        try {
          repository.save(StorageObject.pending(key, hash, contentType, size));
        } catch (DataIntegrityViolationException e) {
          rowPreexisted = true;
        }
      }
      // A freshly inserted row always needs the upload; a pre-existing PENDING row (possibly
      // poisoned, mid-flight, or a race winner's) needs it only when the object is actually absent.
      if (!rowPreexisted || !provider.exists(key)) {
        uploadBuffered(buffer, key, size, contentType);
      }
      return new StagedObject(key, hash, size);
    } finally {
      deleteQuietly(buffer);
    }
  }

  private void uploadBuffered(Path buffer, String key, long size, String contentType) {
    try (InputStream in = Files.newInputStream(buffer)) {
      provider.put(key, in, size, contentType);
    } catch (IOException e) {
      throw new StorageException("Failed to read buffered upload for " + key, e);
    }
  }

  /** Marks the object committed (its domain row is now durable). Idempotent; no-op if unknown. */
  @Transactional
  public void commit(String key) {
    repository.findByObjectKey(key).ifPresent(object -> object.markCommitted(Instant.now()));
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
   * admin can disable it, put it in dry-run, or trigger a run-now; the gate owns the transaction
   * around {@link #reapOrphansOnce(boolean)}.
   */
  @Scheduled(cron = "${qnop.s3.reaper-cron:0 30 3 * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.STORAGE_ORPHAN_REAPER, lockAtMostFor = "PT10M")
  public void reapOrphans() {
    scheduler.runScheduled(SchedulerJobCatalog.STORAGE_ORPHAN_REAPER);
  }

  /**
   * The raw reaper pass, run inside the scheduler gate's transaction (issue #524). The object is
   * removed before its row, so a crash mid-sweep simply retries next run (delete is idempotent). In
   * {@code dryRun} mode it counts what would be deleted but deletes nothing — a safe operator
   * probe.
   */
  public void reapOrphansOnce(boolean dryRun) {
    Instant cutoff = Instant.now().minus(properties.reaperGracePeriod());
    List<StorageObject> orphans =
        repository.findByStatusAndCreatedAtBefore(StorageObjectStatus.PENDING, cutoff);
    if (orphans.isEmpty()) {
      return;
    }
    if (dryRun) {
      log.info(
          "Storage orphan reaper (dry-run) would delete {} uncommitted object(s).", orphans.size());
      return;
    }
    for (StorageObject orphan : orphans) {
      provider.delete(orphan.getObjectKey());
      repository.delete(orphan);
    }
    log.info("Storage orphan reaper deleted {} uncommitted object(s).", orphans.size());
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
