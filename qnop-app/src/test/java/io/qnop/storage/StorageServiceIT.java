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

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.StorageObjectStatus;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

  private static byte[] uniqueContent() {
    return ("qnop-doc-" + UUID.randomUUID()).getBytes(UTF_8);
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

    storage.delete(staged.key());
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
    storage.delete(staged.key());
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
    storage.delete(staged.key());
  }

  @Test
  void identicalContentDeduplicates() {
    byte[] data = uniqueContent();

    StagedObject first = stage(data, "application/pdf");
    StagedObject second = stage(data, "application/pdf");

    assertThat(second.key()).isEqualTo(first.key());
    assertThat(repository.findByObjectKey(first.key())).isPresent();
    storage.delete(first.key());
  }

  @Test
  void deleteIsIdempotent() {
    StagedObject staged = stage(uniqueContent(), "application/pdf");

    storage.delete(staged.key());
    storage.delete(staged.key()); // second delete must not fail

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

    storage.reapOrphans();

    assertThat(repository.findByObjectKey(orphan.key())).isEmpty();
    assertThat(storage.get(orphan.key())).isEmpty();
    assertThat(repository.findByObjectKey(committed.key())).isPresent();
    assertThat(storage.get(committed.key())).isPresent();

    storage.delete(committed.key());
  }

  @Test
  void recentUncommittedObjectSurvivesTheReaper() {
    StagedObject fresh = stage(uniqueContent(), "application/pdf");

    storage.reapOrphans(); // within the grace period → not reaped

    assertThat(repository.findByObjectKey(fresh.key())).isPresent();
    assertThat(storage.get(fresh.key())).isPresent();
    storage.delete(fresh.key());
  }
}
