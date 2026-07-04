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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.spi.storage.StorageException;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Key-format defense-in-depth (issue #337): every entry point must reject anything that is not a
 * canonical {@code sha256/<2-hex>/<64-hex>} key <em>before</em> it reaches S3. {@code @ValueSource}
 * needs compile-time constants, so the 64-hex body is spelled out via {@link #Z64}.
 */
class S3StorageProviderTest {

  private static final String Z64 =
      "0000000000000000000000000000000000000000000000000000000000000000";
  private static final String VALID_KEY = "sha256/00/" + Z64; // shard '00' matches the hash prefix

  private final S3Client s3 = mock(S3Client.class);
  private final S3StorageProvider provider = new S3StorageProvider(s3, "qnop");

  @ParameterizedTest(name = "rejects [{0}]")
  @NullSource
  @ValueSource(
      strings = {
        "", // empty
        "foo/bar", // foreign scheme
        "sha256/aa/deadbeef", // hash too short
        "sha256/zz/" + Z64, // non-hex shard
        "sha256/AA/" + Z64, // uppercase shard
        "sha256/aa/../../etc/passwd", // traversal segments
        "sha256/ab/" + Z64, // shard does not match the hash prefix (ab vs 00)
        "sha256/00/" + Z64 + "/extra", // trailing segment
        "../sha256/00/" + Z64, // leading traversal
      })
  @DisplayName("get rejects a malformed key before any S3 call")
  void getRejectsMalformedKey(String key) {
    assertThatThrownBy(() -> provider.get(key)).isInstanceOf(StorageException.class);
    verifyNoInteractions(s3);
  }

  @ParameterizedTest(name = "rejects [{0}]")
  @NullSource
  @ValueSource(strings = {"sha256/aa/short", "sha256/ab/" + Z64})
  @DisplayName("put rejects a malformed key before any S3 call")
  void putRejectsMalformedKey(String key) {
    assertThatThrownBy(
            () ->
                provider.put(key, new ByteArrayInputStream(new byte[] {1}), 1L, "application/pdf"))
        .isInstanceOf(StorageException.class);
    verifyNoInteractions(s3);
  }

  @ParameterizedTest(name = "rejects [{0}]")
  @NullSource
  @ValueSource(strings = {"nope", "sha256/aa/short"})
  @DisplayName("exists and delete reject a malformed key before any S3 call")
  void existsAndDeleteRejectMalformedKey(String key) {
    assertThatThrownBy(() -> provider.exists(key)).isInstanceOf(StorageException.class);
    assertThatThrownBy(() -> provider.delete(key)).isInstanceOf(StorageException.class);
    verifyNoInteractions(s3);
  }

  @Test
  @DisplayName("a canonical key passes validation and reaches S3")
  void validKeyReachesS3() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());

    assertThat(provider.exists(VALID_KEY)).isTrue();

    verify(s3).headObject(any(HeadObjectRequest.class));
  }
}
