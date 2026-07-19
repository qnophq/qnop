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

import io.qnop.entity.Document;
import io.qnop.repository.AttachmentStorageRef;
import io.qnop.repository.VersionStorageRef;
import io.qnop.service.storage.StorageConsistencyService.MissingBinaryView;
import io.qnop.service.storage.StorageConsistencyService.MissingKind;
import io.qnop.service.storage.StorageConsistencyService.OrphanView;
import io.qnop.service.storage.StorageConsistencyService.Partition;
import io.qnop.spi.storage.StorageListing;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure diff and mapping rules of the storage-consistency scan (issue #523, ADR-0043) —
 * partitioning known/orphaned/missing, the circuit breaker, and missing-key enrichment — exercised
 * without a database or a live bucket.
 */
class StorageConsistencyServiceTest {

  private static final Instant T = Instant.parse("2026-07-19T10:00:00Z");
  private static final int NO_LIMIT = Integer.MAX_VALUE;

  private static StorageListing listing(String key) {
    return new StorageListing(key, 1024L, T);
  }

  @Test
  @DisplayName(
      "partitions a bucket into orphaned objects and missing keys around the referenced set")
  void partitionsKnownOrphanedMissing() {
    Set<String> referenced = Set.of("a", "b", "c");
    // Bucket has a, b (known) and x (orphan); c is referenced but absent (missing).
    List<StorageListing> bucket = List.of(listing("a"), listing("b"), listing("x"));

    Partition result = StorageConsistencyService.partition(referenced, bucket.iterator(), NO_LIMIT);

    assertThat(result.orphaned()).extracting(OrphanView::storageKey).containsExactly("x");
    assertThat(result.missingKeys()).containsExactly("c");
  }

  @Test
  @DisplayName("an empty bucket makes every referenced key missing")
  void emptyBucketAllMissing() {
    Partition result =
        StorageConsistencyService.partition(
            Set.of("a", "b"), List.<StorageListing>of().iterator(), NO_LIMIT);

    assertThat(result.orphaned()).isEmpty();
    assertThat(result.missingKeys()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  @DisplayName("with no references every object is an orphan and nothing is missing")
  void noReferencesAllOrphaned() {
    Partition result =
        StorageConsistencyService.partition(
            Set.of(), List.of(listing("x"), listing("y")).iterator(), NO_LIMIT);

    assertThat(result.orphaned())
        .extracting(OrphanView::storageKey)
        .containsExactlyInAnyOrder("x", "y");
    assertThat(result.missingKeys()).isEmpty();
  }

  @Test
  @DisplayName("an orphan carries its size and last-modified time")
  void orphanCarriesMetadata() {
    StorageListing entry = new StorageListing("x", 4096L, T);

    Partition result =
        StorageConsistencyService.partition(Set.of(), List.of(entry).iterator(), NO_LIMIT);

    assertThat(result.orphaned())
        .singleElement()
        .satisfies(
            orphan -> {
              assertThat(orphan.size()).isEqualTo(4096L);
              assertThat(orphan.lastModified()).isEqualTo(T);
            });
  }

  @Test
  @DisplayName("the circuit breaker aborts once more than maxKeys objects have been streamed")
  void circuitBreakerAborts() {
    List<StorageListing> bucket = List.of(listing("x"), listing("y"), listing("z"));

    assertThatThrownBy(() -> StorageConsistencyService.partition(Set.of(), bucket.iterator(), 2))
        .isInstanceOf(StorageScanLimitExceededException.class)
        .hasMessageContaining("2");
  }

  @Test
  @DisplayName("maps missing version and attachment refs to findings with document context")
  void mapsMissingRefs() {
    UUID docId = UUID.randomUUID();
    Document document = new Document(UUID.randomUUID(), "Master services agreement");
    document.setSlug("msa");
    // A dedup-shared key referenced by both a version and an attachment yields one finding each.
    List<VersionStorageRef> versionRefs = List.of(new VersionStorageRef("k1", docId, 3));
    List<AttachmentStorageRef> attachmentRefs =
        List.of(new AttachmentStorageRef("k2", docId, "diagram.png"));

    List<MissingBinaryView> views =
        StorageConsistencyService.toMissingViews(
            versionRefs, attachmentRefs, Map.of(docId, document));

    assertThat(views).hasSize(2);
    assertThat(views.get(0))
        .satisfies(
            v -> {
              assertThat(v.kind()).isEqualTo(MissingKind.VERSION);
              assertThat(v.storageKey()).isEqualTo("k1");
              assertThat(v.documentTitle()).isEqualTo("Master services agreement");
              assertThat(v.documentSlug()).isEqualTo("msa");
              assertThat(v.versionNumber()).isEqualTo(3);
              assertThat(v.attachmentName()).isNull();
            });
    assertThat(views.get(1))
        .satisfies(
            v -> {
              assertThat(v.kind()).isEqualTo(MissingKind.ATTACHMENT);
              assertThat(v.attachmentName()).isEqualTo("diagram.png");
              assertThat(v.versionNumber()).isNull();
            });
  }

  @Test
  @DisplayName("an unresolved document leaves title and slug null, never a raw id")
  void unresolvedDocumentYieldsNulls() {
    UUID docId = UUID.randomUUID();

    List<MissingBinaryView> views =
        StorageConsistencyService.toMissingViews(
            List.of(new VersionStorageRef("k1", docId, 1)), List.of(), Map.of());

    assertThat(views)
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.documentTitle()).isNull();
              assertThat(v.documentSlug()).isNull();
            });
  }

  @Test
  @DisplayName("no missing refs yields no findings")
  void emptyMissingRefs() {
    assertThat(StorageConsistencyService.toMissingViews(List.of(), List.of(), Map.of())).isEmpty();
  }
}
