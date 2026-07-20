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

import static java.util.stream.Collectors.toMap;

import io.qnop.entity.Document;
import io.qnop.repository.AttachmentStorageRef;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.repository.VersionStorageRef;
import io.qnop.spi.storage.StorageListing;
import io.qnop.spi.storage.StorageProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Reconciles the document binaries in object storage against the database for the admin
 * storage-consistency dashboard (issue #523, ADR-0044). It surfaces two finding classes:
 *
 * <ul>
 *   <li><b>Missing binaries</b> — a {@code document_version}/{@code document_attachment} row whose
 *       {@code storage_key} is absent from storage (data loss). Report-only.
 *   <li><b>Orphaned objects</b> — a stored object no database row references (cost/leak).
 * </ul>
 *
 * <p>The referenced set is the union of {@code document_version.storage_key}, {@code
 * document_attachment.storage_key} and the {@code storage_object} registry keys — including PENDING
 * staging rows, so an in-flight upload never looks like an orphan. Avatars/branding live in
 * Postgres {@code bytea} (ADR-0024/0031), not object storage, so they are out of scope.
 *
 * <p>The core diff is the pure, DB-free static {@link #partition} (with its circuit breaker) and
 * the pure {@link #toMissingViews} mapper — both unit-testable without an {@code EntityManager} or
 * a live bucket (the architecture guardrail, ADR-0004). The bucket is <em>streamed</em> (never
 * materialised) and, deliberately, outside any long-lived transaction.
 */
@Service
public class StorageConsistencyService {

  /**
   * The scan lists the whole bucket: by ADR-0005 only document binaries ({@code sha256/…}) are
   * written here, so any object outside that shape is itself an out-of-band orphan worth surfacing.
   */
  static final String SCAN_PREFIX = "";

  private final StorageProvider storage;
  private final DocumentVersionRepository documentVersions;
  private final DocumentAttachmentRepository documentAttachments;
  private final StorageObjectRepository storageObjects;
  private final DocumentRepository documents;
  private final int maxKeys;

  public StorageConsistencyService(
      StorageProvider storage,
      DocumentVersionRepository documentVersions,
      DocumentAttachmentRepository documentAttachments,
      StorageObjectRepository storageObjects,
      DocumentRepository documents,
      S3Properties properties) {
    this.storage = storage;
    this.documentVersions = documentVersions;
    this.documentAttachments = documentAttachments;
    this.storageObjects = storageObjects;
    this.documents = documents;
    this.maxKeys = properties.consistencyScanMaxKeys();
  }

  /** Which kind of row references a missing binary. */
  public enum MissingKind {
    VERSION,
    ATTACHMENT
  }

  /** A stored object no database row references. */
  public record OrphanView(String storageKey, long size, Instant lastModified) {}

  /** A referenced binary whose object is gone, with the context an admin needs to act. */
  public record MissingBinaryView(
      String storageKey,
      MissingKind kind,
      UUID documentId,
      String documentTitle,
      String documentSlug,
      Integer versionNumber,
      String attachmentName) {}

  public record ConsistencySummary(
      long dbReferencedCount,
      long storageObjectCount,
      int missingCount,
      int orphanedCount,
      Instant scannedAt) {}

  public record ConsistencyReport(
      ConsistencySummary summary, List<MissingBinaryView> missing, List<OrphanView> orphaned) {}

  /**
   * Runs a full scan: loads the referenced set, streams the bucket, partitions into known/orphaned,
   * derives the missing set, and enriches missing keys with document context. Throws {@link
   * StorageScanLimitExceededException} if the bucket exceeds the configured circuit-breaker limit.
   */
  public ConsistencyReport scan() {
    List<String> versionKeys = documentVersions.findAllStorageKeys();
    List<String> attachmentKeys = documentAttachments.findAllStorageKeys();
    List<String> registryKeys = storageObjects.findAllObjectKeys();

    Set<String> referenced = new HashSet<>();
    referenced.addAll(versionKeys);
    referenced.addAll(attachmentKeys);
    referenced.addAll(registryKeys);

    // The bucket stream is consumed outside any transaction so a long scan never pins a DB
    // connection; try-with-resources closes the SDK-backed stream.
    Partition partition;
    try (Stream<StorageListing> listing = storage.list(SCAN_PREFIX)) {
      partition = partition(referenced, listing.iterator(), maxKeys);
    }

    List<MissingBinaryView> missing = enrichMissing(partition.missingKeys());
    ConsistencySummary summary =
        new ConsistencySummary(
            (long) versionKeys.size() + attachmentKeys.size(),
            registryKeys.size(),
            missing.size(),
            partition.orphaned().size(),
            Instant.now());
    return new ConsistencyReport(summary, missing, partition.orphaned());
  }

  /** The bucket-vs-referenced diff. */
  record Partition(List<OrphanView> orphaned, Set<String> missingKeys) {}

  /**
   * The pure diff (DB-free, S3-free): streams the listing once, collecting orphans (not referenced)
   * and tracking which referenced keys were seen; missing = referenced − seen. The circuit breaker
   * aborts after {@code maxKeys} streamed objects so a pathological bucket fails fast.
   */
  static Partition partition(
      Set<String> referenced, Iterator<StorageListing> listing, int maxKeys) {
    Set<String> found = new HashSet<>();
    List<OrphanView> orphaned = new ArrayList<>();
    int scanned = 0;
    while (listing.hasNext()) {
      StorageListing entry = listing.next();
      if (++scanned > maxKeys) {
        throw new StorageScanLimitExceededException(
            "Storage scan exceeded the limit of " + maxKeys + " objects");
      }
      if (referenced.contains(entry.key())) {
        found.add(entry.key());
      } else {
        orphaned.add(new OrphanView(entry.key(), entry.size(), entry.lastModified()));
      }
    }
    Set<String> missing = new HashSet<>(referenced);
    missing.removeAll(found);
    return new Partition(orphaned, missing);
  }

  private List<MissingBinaryView> enrichMissing(Set<String> missingKeys) {
    if (missingKeys.isEmpty()) {
      return List.of();
    }
    List<VersionStorageRef> versionRefs =
        documentVersions.findVersionRefsByStorageKeyIn(missingKeys);
    List<AttachmentStorageRef> attachmentRefs =
        documentAttachments.findAttachmentRefsByStorageKeyIn(missingKeys);

    Set<UUID> documentIds = new HashSet<>();
    versionRefs.forEach(ref -> documentIds.add(ref.documentId()));
    attachmentRefs.forEach(ref -> documentIds.add(ref.documentId()));
    Map<UUID, Document> documentById =
        documents.findAllById(documentIds).stream().collect(toMap(Document::getId, doc -> doc));

    return toMissingViews(versionRefs, attachmentRefs, documentById);
  }

  /**
   * The pure mapping from missing-key references to views, given the pre-resolved documents —
   * DB-free so it is unit-testable in isolation. A content-addressed key may be shared by several
   * rows (dedup), so one missing object can produce several findings, one per affected document.
   */
  static List<MissingBinaryView> toMissingViews(
      List<VersionStorageRef> versionRefs,
      List<AttachmentStorageRef> attachmentRefs,
      Map<UUID, Document> documentById) {
    List<MissingBinaryView> views = new ArrayList<>(versionRefs.size() + attachmentRefs.size());
    for (VersionStorageRef ref : versionRefs) {
      Document document = documentById.get(ref.documentId());
      views.add(
          new MissingBinaryView(
              ref.storageKey(),
              MissingKind.VERSION,
              ref.documentId(),
              document == null ? null : document.getTitle(),
              document == null ? null : document.getSlug(),
              ref.versionNumber(),
              null));
    }
    for (AttachmentStorageRef ref : attachmentRefs) {
      Document document = documentById.get(ref.documentId());
      views.add(
          new MissingBinaryView(
              ref.storageKey(),
              MissingKind.ATTACHMENT,
              ref.documentId(),
              document == null ? null : document.getTitle(),
              document == null ? null : document.getSlug(),
              null,
              ref.fileName()));
    }
    return views;
  }
}
