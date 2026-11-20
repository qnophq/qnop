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

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The object-storage extension point (ADR-0005): store, retrieve, probe and delete binary content
 * by opaque key. The Community default is an S3/MinIO adapter; the S3 API is the abstraction (no
 * multi-cloud layer — ADR-0005), so keys are plain strings and content streams both ways.
 *
 * <p>Implementations must be safe for concurrent use. All failures surface as {@link
 * StorageException}; a store that cannot be reached, or fails on its own side, should say so with
 * the subtype {@link StorageUnavailableException}, which the web layer answers with 503 rather than
 * 500.
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
   * Stores the content under {@code key}, as {@link #put(String, InputStream, long, String)} does,
   * but from content that can be opened again (issue #983). A separate name rather than an overload
   * of {@code put}, so a caller's or a test's {@code put(…, any(), …)} stays unambiguous. A
   * provider that retries a failed attempt should override this and open the content anew for each
   * attempt; a one-shot stream cannot be replayed. The core stores every upload this way.
   *
   * <p>This is an additive, backward-compatible extension to the contract (SemVer minor): the
   * {@code default} opens the content once and passes it to the stream variant, so a provider that
   * does not retry keeps compiling and working unchanged.
   *
   * <p>A provider that wraps another one, to measure or log its calls, must forward this method to
   * the wrapped provider as well. Otherwise the default sends the content through the stream
   * variant, and the wrapped provider can no longer retry. A test that mocks this interface sees
   * the core's uploads here, not on {@code put}: a mock does not run the default.
   *
   * @param key the object key
   * @param content the bytes to store; each {@link ContentSource#open()} starts from the first byte
   * @param contentLength the exact number of bytes in the content
   * @param contentType the MIME type to record with the object
   */
  default void putReopenable(
      String key, ContentSource content, long contentLength, String contentType) {
    InputStream stream;
    try {
      stream = content.open();
    } catch (IOException e) {
      throw new StorageException("Failed to read the content to store", e);
    }
    try {
      put(key, stream, contentLength, contentType);
    } finally {
      try {
        stream.close();
      } catch (IOException ignored) {
        // The content was only read: a stream that fails to close loses nothing that was stored.
      }
    }
  }

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
   * Lists every object whose key starts with {@code prefix} (issue #523, ADR-0044), for the
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
