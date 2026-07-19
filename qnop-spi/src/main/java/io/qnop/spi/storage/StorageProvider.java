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
package io.qnop.spi.storage;

import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The object-storage extension point (ADR-0005): store, retrieve, probe and delete binary content
 * by opaque key. The Community default is an S3/MinIO adapter; the S3 API is the abstraction (no
 * multi-cloud layer — ADR-0005), so keys are plain strings and content streams both ways.
 *
 * <p>Implementations must be safe for concurrent use. All failures surface as {@link
 * StorageException}.
 */
public interface StorageProvider {

  /**
   * Stores {@code content} under {@code key}, overwriting any existing object with that key.
   *
   * @param key the object key
   * @param content the bytes to store; the caller retains ownership and closes it
   * @param contentLength the exact number of bytes in {@code content}
   * @param contentType the MIME type to record with the object
   */
  void put(String key, InputStream content, long contentLength, String contentType);

  /**
   * Opens the object at {@code key} for reading, or {@link Optional#empty()} if none exists. The
   * caller must close the returned {@link StorageContent} (it owns the underlying stream).
   */
  Optional<StorageContent> get(String key);

  /** Whether an object exists at {@code key}. */
  boolean exists(String key);

  /**
   * Deletes the object at {@code key}. Idempotent.
   *
   * @return {@code true} if an object existed and was deleted, {@code false} if there was none
   */
  boolean delete(String key);

  /**
   * Lists every object whose key starts with {@code prefix} (issue #523, ADR-0043), for the
   * storage-consistency scan's orphan direction. The returned stream is <em>lazy</em> and may be
   * backed by a network connection: the caller must consume it inside a try-with-resources block so
   * it is closed. Order is unspecified.
   *
   * <p>This is an additive, backward-compatible extension to the contract (SemVer minor): it is a
   * {@code default} method that throws {@link UnsupportedOperationException}, so a provider that
   * cannot enumerate its backing store keeps compiling unchanged and the scan degrades gracefully.
   * The S3/MinIO Community default overrides it via paginated {@code ListObjectsV2}.
   *
   * @param prefix the key prefix to scope the listing to (empty string lists the whole bucket)
   * @return a lazy stream of the matching objects with their size and last-modified time
   */
  default Stream<StorageListing> list(String prefix) {
    throw new UnsupportedOperationException("This StorageProvider does not support listing");
  }
}
