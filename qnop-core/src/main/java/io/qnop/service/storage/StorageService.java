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
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

  public StorageService(
      StorageProvider provider, StorageObjectRepository repository, S3Properties properties) {
    this.provider = provider;
    this.repository = repository;
    this.properties = properties;
  }

  /**
   * Buffers the content (to compute its hash and length), uploads it under a content-addressed key,
   * and records a {@code PENDING} registry row. Idempotent for identical content: an already-known
   * key is returned without re-uploading (dedup).
   */
  public StagedObject stage(InputStream content, String contentType) {
    Path buffer = bufferToTempFile(content);
    try {
      String hash = sha256(buffer);
      long size = sizeOf(buffer);
      String key = keyFor(hash);

      if (repository.findByObjectKey(key).isPresent()) {
        return new StagedObject(key, hash, size); // dedup: content already stored
      }
      // Persist the PENDING row (in Spring Data's own transaction) BEFORE the upload, so a crash
      // between the two always leaves a row the reaper can reclaim. A concurrent stage of identical
      // content may win the unique-key race first — treat that as dedup.
      try {
        repository.save(StorageObject.pending(key, hash, contentType, size));
      } catch (DataIntegrityViolationException e) {
        return new StagedObject(key, hash, size);
      }
      try (InputStream in = Files.newInputStream(buffer)) {
        provider.put(key, in, size, contentType);
      } catch (IOException e) {
        throw new StorageException("Failed to read buffered upload for " + key, e);
      }
      return new StagedObject(key, hash, size);
    } finally {
      deleteQuietly(buffer);
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
   * single-instance via ShedLock (ADR-0029); tests invoke it directly. The object is removed before
   * its row, so a crash mid-sweep simply retries next run (delete is idempotent).
   */
  @Scheduled(cron = "${qnop.s3.reaper-cron:0 30 3 * * *}")
  @SchedulerLock(name = "storageOrphanReaper", lockAtMostFor = "PT10M")
  @Transactional
  public void reapOrphans() {
    Instant cutoff = Instant.now().minus(properties.reaperGracePeriod());
    List<StorageObject> orphans =
        repository.findByStatusAndCreatedAtBefore(StorageObjectStatus.PENDING, cutoff);
    for (StorageObject orphan : orphans) {
      provider.delete(orphan.getObjectKey());
      repository.delete(orphan);
    }
    if (!orphans.isEmpty()) {
      log.info("Storage orphan reaper deleted {} uncommitted object(s).", orphans.size());
    }
  }

  /**
   * Content-addressed key: {@code sha256/<first-2-hex>/<full-hex>} (sharded to avoid a flat set).
   */
  private static String keyFor(String hash) {
    return "sha256/" + hash.substring(0, 2) + "/" + hash;
  }

  private static Path bufferToTempFile(InputStream content) {
    try {
      Path temp = Files.createTempFile("qnop-storage-", ".tmp");
      Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
      return temp;
    } catch (IOException e) {
      throw new StorageException("Failed to buffer upload", e);
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
