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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.observability.metrics.StorageMeters;
import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The object store is measured around the provider, whichever provider it is (issue #962). */
class MeteredStorageProviderTest {

  private final StorageProvider delegate = mock(StorageProvider.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final StorageMeters meters = new StorageMeters(registry);
  private final MeteredStorageProvider provider = new MeteredStorageProvider(delegate, meters);

  private static InputStream bytes(int length) {
    return new ByteArrayInputStream(new byte[length]);
  }

  @Test
  @DisplayName("a successful put is timed and its bytes counted as uploaded")
  void putIsTimedAndCounted() {
    provider.put("k", bytes(42), 42, "application/pdf");

    assertThat(calls("put", "success")).isEqualTo(1);
    assertThat(transferred("upload")).isEqualTo(42.0);
    assertThat(transferred("download")).isZero();
  }

  @Test
  @DisplayName("a failed put is timed as an error, passes the exception on and counts no bytes")
  void failedPutCountsNoBytes() {
    StorageException down = new StorageException("store down", null);
    doThrow(down).when(delegate).put(any(), any(), anyLong(), any());

    assertThatThrownBy(() -> provider.put("k", bytes(42), 42, "application/pdf")).isSameAs(down);

    assertThat(calls("put", "error")).isEqualTo(1);
    assertThat(calls("put", "success")).isZero();
    assertThat(transferred("upload")).isZero();
  }

  @Test
  @DisplayName("opening an object counts its size as downloaded; a miss is a success with no bytes")
  void getCountsTheOpenedSize() {
    when(delegate.get("there"))
        .thenReturn(Optional.of(new StorageContent(bytes(7), 7, "application/pdf")));
    when(delegate.get("gone")).thenReturn(Optional.empty());

    assertThat(provider.get("there")).isPresent();
    assertThat(provider.get("gone")).isEmpty();

    // An absent object is an answer from the store, not a failure of it.
    assertThat(calls("get", "success")).isEqualTo(2);
    assertThat(transferred("download")).isEqualTo(7.0);
  }

  @Test
  @DisplayName("exists and delete pass their answers through and are timed")
  void probesPassThrough() {
    when(delegate.exists("k")).thenReturn(true);
    when(delegate.delete("k")).thenReturn(true);

    assertThat(provider.exists("k")).isTrue();
    assertThat(provider.delete("k")).isTrue();

    assertThat(calls("exists", "success")).isEqualTo(1);
    assertThat(calls("delete", "success")).isEqualTo(1);
  }

  @Test
  @DisplayName("a provider that cannot list is not failing: nothing is measured")
  void unsupportedListingIsNotAnError() {
    when(delegate.list("")).thenThrow(new UnsupportedOperationException("no listing"));

    assertThatThrownBy(() -> provider.list("")).isInstanceOf(UnsupportedOperationException.class);

    assertThat(registry.find(StorageMeters.OPERATION).timers()).isEmpty();
  }

  @Test
  @DisplayName("no tag carries an object key")
  void tagsNeverCarryTheKey() {
    provider.exists("sha256/ab/abcdef");

    assertThat(registry.find(StorageMeters.OPERATION).timers())
        .isNotEmpty()
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .containsExactlyInAnyOrder("operation", "outcome"));
  }

  @Test
  @DisplayName("any provider in the context is wrapped once, and nothing else is touched")
  void postProcessorWrapsProvidersOnly() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("storageMeters", meters);
    StorageProviderMetering metering =
        new StorageProviderMetering(beans.getBeanProvider(StorageMeters.class));

    Object wrapped = metering.postProcessAfterInitialization(delegate, "storageProvider");
    assertThat(wrapped).isInstanceOf(MeteredStorageProvider.class);
    assertThat(((MeteredStorageProvider) wrapped).delegate()).isSameAs(delegate);
    // Already measured: not measured twice.
    assertThat(metering.postProcessAfterInitialization(wrapped, "storageProvider"))
        .isSameAs(wrapped);
    Object other = new Object();
    assertThat(metering.postProcessAfterInitialization(other, "other")).isSameAs(other);
  }

  private long calls(String operation, String outcome) {
    var timer =
        registry
            .find(StorageMeters.OPERATION)
            .tag("operation", operation)
            .tag("outcome", outcome)
            .timer();
    return timer == null ? 0 : timer.count();
  }

  private double transferred(String direction) {
    var counter = registry.find(StorageMeters.TRANSFERRED).tag("direction", direction).counter();
    return counter == null ? 0 : counter.count();
  }
}
