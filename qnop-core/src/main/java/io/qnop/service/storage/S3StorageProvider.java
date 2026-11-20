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

import io.qnop.spi.storage.ContentSource;
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageListing;
import io.qnop.spi.storage.StorageProvider;
import io.qnop.spi.storage.StorageUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Community {@link StorageProvider} default (ADR-0005): an S3-compatible adapter over the AWS SDK
 * v2. Configured with {@code endpointOverride} + path-style, it speaks to MinIO (On-Prem) or S3/GCS
 * (SaaS) identically — the S3 API is the abstraction, so there is no multi-cloud layer.
 */
public class S3StorageProvider implements StorageProvider {

  private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

  /**
   * The content-addressed key shape this adapter accepts: {@code sha256/<2-hex-shard>/<64-hex>}, as
   * minted by {@code StorageService.keyFor}. Every entry point rejects anything else before it
   * reaches S3 (issue #337) — defense-in-depth so a malformed or injected key (traversal segments,
   * a foreign prefix, a truncated hash) can never address an object, even though object stores
   * treat keys as opaque flat strings.
   */
  private static final Pattern KEY_PATTERN = Pattern.compile("sha256/([0-9a-f]{2})/([0-9a-f]{64})");

  private final S3Client s3;
  private final String bucket;

  public S3StorageProvider(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  /**
   * Stores a one-shot stream. The SDK cannot replay it, so an attempt that fails is not retried;
   * the core stores through {@link #putReopenable} instead.
   */
  @Override
  public void put(String key, InputStream content, long contentLength, String contentType) {
    requireValidKey(key);
    try {
      upload(key, RequestBody.fromInputStream(content, contentLength), contentLength, contentType);
    } catch (IllegalStateException e) {
      // The SDK's retry of a one-shot stream it has already read: the attempt before it failed.
      throw new StorageException("Failed to store object " + key, e);
    }
  }

  /**
   * Stores content the SDK can open again, so its retry of a failed attempt sends the whole content
   * once more (issue #983). A one-shot stream made that retry fail with an {@link
   * IllegalStateException} that hid the actual failure.
   */
  @Override
  public void putReopenable(
      String key, ContentSource content, long contentLength, String contentType) {
    requireValidKey(key);
    try (ReopenableContent reopenable = new ReopenableContent(content)) {
      try {
        upload(
            key,
            RequestBody.fromContentProvider(reopenable, contentLength, contentType),
            contentLength,
            contentType);
      } catch (StorageUnavailableException e) {
        // The SDK may wrap our own failure to open the content as a client failure; that is a
        // local fault, not the store being unavailable.
        if (reopenable.openFailure != null) {
          throw new StorageException("Failed to read the content to store", reopenable.openFailure);
        }
        throw e;
      }
    }
  }

  private void upload(String key, RequestBody body, long contentLength, String contentType) {
    long started = System.nanoTime();
    try {
      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
          body);
      // Keys are content-addressed and pattern-checked (issue #337): a hash, never a name.
      log.debug("PUT {} ({} bytes, {}) in {} ms", key, contentLength, contentType, millis(started));
    } catch (SdkException | UncheckedIOException e) {
      throw translate("Failed to store object " + key, e);
    }
  }

  @Override
  public Optional<StorageContent> get(String key) {
    requireValidKey(key);
    long started = System.nanoTime();
    try {
      ResponseInputStream<GetObjectResponse> stream =
          s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
      GetObjectResponse response = stream.response();
      log.debug("GET {} ({} bytes) in {} ms", key, response.contentLength(), millis(started));
      return Optional.of(
          new StorageContent(stream, response.contentLength(), response.contentType()));
    } catch (NoSuchKeyException e) {
      log.debug("GET {} missed", key);
      return Optional.empty();
    } catch (SdkException e) {
      throw translate("Failed to read object " + key, e);
    }
  }

  @Override
  public boolean exists(String key) {
    requireValidKey(key);
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (S3Exception e) {
      // headObject reports a missing key as a 404 without a typed exception on some backends.
      if (e.statusCode() == 404) {
        return false;
      }
      throw translate("Failed to probe object " + key, e);
    } catch (SdkException e) {
      throw translate("Failed to probe object " + key, e);
    }
  }

  @Override
  public boolean delete(String key) {
    requireValidKey(key);
    if (!exists(key)) {
      return false;
    }
    try {
      s3.deleteObject(b -> b.bucket(bucket).key(key));
      log.debug("DELETE {}", key);
      return true;
    } catch (SdkException e) {
      throw translate("Failed to delete object " + key, e);
    }
  }

  /**
   * Lists the bucket under {@code prefix} for the consistency scan (issue #523). Deliberately does
   * NOT run discovered keys through {@link #requireValidKey} — the scan's whole purpose is to find
   * objects that do <em>not</em> match the canonical shape (out-of-band writes, leftovers). Uses
   * the SDK's auto-paginating {@code ListObjectsV2} so the stream stays lazy (one page in memory at
   * a time); the caller consumes it in a try-with-resources block. A page that fails to load while
   * the stream is consumed surfaces as a {@link StorageException} too (issue #983).
   */
  @Override
  public Stream<StorageListing> list(String prefix) {
    String failure = "Failed to list objects under prefix";
    try {
      Spliterator<S3Object> pages =
          s3.listObjectsV2Paginator(
                  ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
              .contents()
              .spliterator();
      Spliterator<S3Object> translated =
          new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super S3Object> action) {
              try {
                return pages.tryAdvance(action);
              } catch (SdkException e) {
                throw translate(failure, e);
              }
            }
          };
      return StreamSupport.stream(translated, false)
          .map(object -> new StorageListing(object.key(), object.size(), object.lastModified()));
    } catch (SdkException e) {
      throw translate(failure, e);
    }
  }

  /**
   * The {@link StorageException} for an SDK failure (issue #983). A store that cannot be reached,
   * fails on its own side, or throttles is unavailable for now; anything else is a failure of the
   * request.
   */
  static StorageException translate(String message, RuntimeException e) {
    boolean unavailable =
        switch (e) {
          case SdkServiceException service ->
              service.statusCode() >= 500 || service.isThrottlingException();
          case SdkClientException client -> true;
          default -> false;
        };
    return unavailable
        ? new StorageUnavailableException(message, e)
        : new StorageException(message, e);
  }

  /**
   * Hands the SDK a fresh stream of the content for each attempt, and closes the one before: the
   * SDK may not close a stream it gave up on.
   */
  private static final class ReopenableContent implements ContentStreamProvider, AutoCloseable {

    private final ContentSource source;
    private InputStream current;
    private volatile IOException openFailure;

    ReopenableContent(ContentSource source) {
      this.source = source;
    }

    @Override
    public synchronized InputStream newStream() {
      closeCurrent();
      try {
        current = source.open();
        return current;
      } catch (IOException e) {
        openFailure = e;
        throw new UncheckedIOException("Failed to open the content to store", e);
      }
    }

    @Override
    public synchronized void close() {
      closeCurrent();
    }

    private void closeCurrent() {
      if (current == null) {
        return;
      }
      try {
        current.close();
      } catch (IOException e) {
        log.debug("Could not close a stream of upload content: {}", e.getMessage());
      }
      current = null;
    }
  }

  private static long millis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  /**
   * Fails an operation whose key is not a canonical content-addressed key (issue #337). The message
   * deliberately does not echo the rejected key, so a crafted value can neither poison logs nor
   * leak back to a caller.
   */
  private static void requireValidKey(String key) {
    Matcher matcher = key == null ? null : KEY_PATTERN.matcher(key);
    if (matcher == null || !matcher.matches() || !matcher.group(2).startsWith(matcher.group(1))) {
      throw new StorageException("Rejected storage key with an unexpected format");
    }
  }
}
