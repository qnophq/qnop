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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.StorageObject;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.spi.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB-free unit tests for {@link StorageService#stage} dedup correctness (issue #289): only {@code
 * COMMITTED} rows are authoritative, and a {@code PENDING} row is (re-)uploaded when the object is
 * actually missing — so a failed earlier {@code put} can never poison the key.
 */
class StorageServiceTest {

  private final StorageProvider provider = mock(StorageProvider.class);
  private final StorageObjectRepository repository = mock(StorageObjectRepository.class);
  private final StorageService storage =
      new StorageService(provider, repository, mock(S3Properties.class));

  private static InputStream content() {
    return new ByteArrayInputStream("some qnop document bytes".getBytes());
  }

  private static StorageObject pendingRow() {
    return StorageObject.pending("k", "h", "application/pdf", 1L);
  }

  private static StorageObject committedRow() {
    StorageObject row = pendingRow();
    row.markCommitted(Instant.now());
    return row;
  }

  @Test
  @DisplayName("staging aborts over the byte limit before any upload (issue #361)")
  void overLimitContentIsRejectedBeforeUpload() {
    InputStream tenBytes = new ByteArrayInputStream("0123456789".getBytes());

    Assertions.assertThatThrownBy(() -> storage.stage(tenBytes, "application/pdf", 4L))
        .isInstanceOf(StorageQuotaExceededException.class);

    verify(provider, never()).put(anyString(), any(), anyLong(), anyString());
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("content exactly at the byte limit still stages")
  void contentAtLimitIsStaged() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.empty());
    InputStream fourBytes = new ByteArrayInputStream("abcd".getBytes());

    storage.stage(fourBytes, "application/pdf", 4L);

    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }

  @Test
  @DisplayName("a COMMITTED hit dedups without uploading or a HEAD check")
  void committedRowIsAuthoritativeDedup() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(committedRow()));

    storage.stage(content(), "application/pdf");

    verify(provider, never()).put(anyString(), any(), anyLong(), anyString());
    verify(provider, never()).exists(anyString());
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("a poisoned PENDING hit (object missing) re-uploads — the fix")
  void pendingRowWithMissingObjectIsReuploaded() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(pendingRow()));
    when(provider.exists(anyString())).thenReturn(false);

    storage.stage(content(), "application/pdf");

    verify(provider).exists(anyString());
    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
    verify(repository, never()).save(any()); // row already exists — not re-inserted
  }

  @Test
  @DisplayName("a PENDING hit whose object exists (mid-flight) is not re-uploaded")
  void pendingRowWithExistingObjectIsNotReuploaded() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.of(pendingRow()));
    when(provider.exists(anyString())).thenReturn(true);

    storage.stage(content(), "application/pdf");

    verify(provider).exists(anyString());
    verify(provider, never()).put(anyString(), any(), anyLong(), anyString());
  }

  @Test
  @DisplayName("fresh content inserts the row and uploads, with no wasted HEAD check")
  void freshContentInsertsAndUploads() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.empty());

    storage.stage(content(), "application/pdf");

    verify(repository).save(any(StorageObject.class));
    verify(provider, never()).exists(anyString());
    verify(provider).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }

  @Test
  @DisplayName("a lost insert race (DataIntegrityViolation) still verify-and-(re)uploads")
  void concurrentInsertRaceIsVerifiedNotTrusted() {
    when(repository.findByObjectKey(anyString())).thenReturn(Optional.empty());
    when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup key"));
    when(provider.exists(anyString())).thenReturn(false); // race winner's put had failed too

    storage.stage(content(), "application/pdf");

    verify(provider).exists(anyString());
    verify(provider, times(1)).put(anyString(), any(), anyLong(), eq("application/pdf"));
  }
}
