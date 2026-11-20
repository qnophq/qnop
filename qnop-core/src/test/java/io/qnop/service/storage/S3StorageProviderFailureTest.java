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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * How the S3 adapter fails (issue #983): every SDK failure surfaces as a {@link StorageException},
 * an unreachable or failing store as a {@link StorageUnavailableException}, and a PUT whose first
 * attempt fails is retried with the whole content. The client is the real SDK client, so its own
 * retry runs; only the HTTP layer underneath is scripted.
 */
class S3StorageProviderFailureTest {

  private static final String KEY = "sha256/00/" + "0".repeat(64);
  private static final byte[] CONTENT =
      "qnop document bytes, sent whole on every attempt".getBytes(UTF_8);

  private final ScriptedHttpClient http = new ScriptedHttpClient();
  private final S3Client s3 =
      S3Client.builder()
          .httpClient(http)
          .region(Region.EU_CENTRAL_1)
          .endpointOverride(URI.create("http://storage.invalid:9000"))
          .forcePathStyle(true)
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
          .build();
  private final S3StorageProvider provider = new S3StorageProvider(s3, "qnop");

  @AfterEach
  void closeClient() {
    s3.close();
  }

  @Test
  @DisplayName("a PUT whose first attempt drops is retried with the whole content, and succeeds")
  void reopenablePutIsRetried() {
    http.failFirst(1);

    provider.putReopenable(KEY, () -> unmarkable(CONTENT), CONTENT.length, "application/pdf");

    assertThat(http.bodies).hasSize(2);
    // The second attempt carries the content again, not the remainder of a spent stream.
    assertThat(http.bodies.get(1)).contains(new String(CONTENT, UTF_8));
  }

  @Test
  @DisplayName("a one-shot stream that cannot be retried fails as a StorageException")
  void oneShotPutFailsAsAStorageException() {
    http.failFirst(1);

    assertThatThrownBy(
            () -> provider.put(KEY, unmarkable(CONTENT), CONTENT.length, "application/pdf"))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("Failed to store object");
  }

  @Test
  @DisplayName("an unreachable store is unavailable, whichever operation finds out")
  void unreachableStoreIsUnavailable() {
    http.failFirst(Integer.MAX_VALUE);

    assertThatThrownBy(
            () ->
                provider.putReopenable(
                    KEY, () -> unmarkable(CONTENT), CONTENT.length, "application/pdf"))
        .isInstanceOf(StorageUnavailableException.class);
    assertThatThrownBy(() -> provider.get(KEY)).isInstanceOf(StorageUnavailableException.class);
    assertThatThrownBy(() -> provider.exists(KEY)).isInstanceOf(StorageUnavailableException.class);
    assertThatThrownBy(() -> provider.delete(KEY)).isInstanceOf(StorageUnavailableException.class);
    assertThatThrownBy(
            () -> {
              try (Stream<?> listing = provider.list("sha256/")) {
                listing.count();
              }
            })
        .isInstanceOf(StorageUnavailableException.class);
  }

  @Test
  @DisplayName("a server error or throttling is unavailable; a refused request is not")
  void serviceErrorsAreTranslatedByStatus() {
    S3Client mocked = mock(S3Client.class);
    S3StorageProvider over = new S3StorageProvider(mocked, "qnop");

    when(mocked.headObject(any(HeadObjectRequest.class))).thenThrow(serviceError(503));
    assertThatThrownBy(() -> over.exists(KEY)).isInstanceOf(StorageUnavailableException.class);

    when(mocked.headObject(any(HeadObjectRequest.class))).thenThrow(serviceError(429));
    assertThatThrownBy(() -> over.exists(KEY)).isInstanceOf(StorageUnavailableException.class);

    when(mocked.headObject(any(HeadObjectRequest.class))).thenThrow(serviceError(403));
    assertThatThrownBy(() -> over.exists(KEY))
        .isInstanceOf(StorageException.class)
        .isNotInstanceOf(StorageUnavailableException.class);
  }

  private static S3Exception serviceError(int status) {
    return (S3Exception)
        S3Exception.builder()
            .statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("Status" + status).build())
            .message("status " + status)
            .build();
  }

  /** A stream the SDK cannot rewind, as a network or multipart stream is. */
  private static InputStream unmarkable(byte[] bytes) {
    return new FilterInputStream(new ByteArrayInputStream(bytes)) {
      @Override
      public boolean markSupported() {
        return false;
      }
    };
  }

  /**
   * An HTTP layer whose first {@code failures} attempts read the request body and then drop the
   * connection, as a reset mid-upload does; later attempts succeed with an empty 200.
   */
  private static final class ScriptedHttpClient implements SdkHttpClient {

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int failures;

    void failFirst(int count) {
      failures = count;
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
      return new ExecutableHttpRequest() {
        @Override
        public HttpExecuteResponse call() throws IOException {
          if (request.contentStreamProvider().isPresent()) {
            try (InputStream body = request.contentStreamProvider().get().newStream()) {
              bodies.add(new String(body.readAllBytes(), UTF_8));
            }
          }
          if (failures > 0) {
            failures--;
            throw new IOException("Connection reset");
          }
          return HttpExecuteResponse.builder()
              .response(
                  SdkHttpResponse.builder().statusCode(200).putHeader("ETag", "\"etag\"").build())
              .responseBody(AbortableInputStream.create(new ByteArrayInputStream(new byte[0])))
              .build();
        }

        @Override
        public void abort() {}
      };
    }

    @Override
    public void close() {}
  }
}
