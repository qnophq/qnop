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

import io.qnop.observability.metrics.StorageMeters;
import io.qnop.observability.metrics.StorageMeters.Direction;
import io.qnop.observability.metrics.StorageMeters.Operation;
import io.qnop.observability.metrics.StorageMeters.Outcome;
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageListing;
import io.qnop.spi.storage.StorageProvider;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Measures every call to the {@link StorageProvider} it wraps (issue #962, ADR-0065) and changes
 * nothing else: arguments, results and exceptions pass through untouched.
 *
 * <p>The services of this package wrap the provider they are given ({@link #around}); the bean in
 * the context stays the provider itself. Replacing the bean instead would hide whatever else an
 * add-on's provider is to Spring: a lifecycle, a listener, a health indicator.
 *
 * <p>A {@code get} is timed until the object is opened, a {@code list} until the lazy stream is
 * returned; what the caller then spends reading is the caller's time, not the store's.
 */
final class MeteredStorageProvider implements StorageProvider {

  private final StorageProvider delegate;
  private final StorageMeters meters;

  private MeteredStorageProvider(StorageProvider delegate, StorageMeters meters) {
    this.delegate = delegate;
    this.meters = meters;
  }

  /** {@code provider}, measured; a provider that already is, is returned as it is. */
  static StorageProvider around(StorageProvider provider, StorageMeters meters) {
    return provider instanceof MeteredStorageProvider
        ? provider
        : new MeteredStorageProvider(provider, meters);
  }

  @Override
  public void put(String key, InputStream content, long contentLength, String contentType) {
    timed(
        Operation.PUT,
        () -> {
          delegate.put(key, content, contentLength, contentType);
          return null;
        });
    meters.transferred(Direction.UPLOAD, contentLength);
  }

  @Override
  public Optional<StorageContent> get(String key) {
    Optional<StorageContent> content = timed(Operation.GET, () -> delegate.get(key));
    // A provider that does not know the length says so with a negative one; that is no bytes.
    content.ifPresent(
        opened -> meters.transferred(Direction.DOWNLOAD, Math.max(0, opened.contentLength())));
    return content;
  }

  @Override
  public boolean exists(String key) {
    return timed(Operation.EXISTS, () -> delegate.exists(key));
  }

  @Override
  public boolean delete(String key) {
    return timed(Operation.DELETE, () -> delegate.delete(key));
  }

  @Override
  public Stream<StorageListing> list(String prefix) {
    return timed(Operation.LIST, () -> delegate.list(prefix));
  }

  private <T> T timed(Operation operation, Supplier<T> call) {
    long started = System.nanoTime();
    Outcome outcome = Outcome.ERROR;
    try {
      T result = call.get();
      outcome = Outcome.SUCCESS;
      return result;
    } catch (UnsupportedOperationException unsupported) {
      // A provider that cannot list is not failing: the scan degrades and nothing is measured.
      // From any other call it is a defect like any other exception.
      if (operation == Operation.LIST) {
        outcome = null;
      }
      throw unsupported;
    } finally {
      if (outcome != null) {
        meters.called(operation, outcome, Duration.ofNanos(System.nanoTime() - started));
      }
    }
  }
}
