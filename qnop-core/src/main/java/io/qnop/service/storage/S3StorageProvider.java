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

import io.qnop.spi.storage.StorageContent;
import io.qnop.spi.storage.StorageException;
import io.qnop.spi.storage.StorageProvider;
import java.io.InputStream;
import java.util.Optional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Community {@link StorageProvider} default (ADR-0005): an S3-compatible adapter over the AWS SDK
 * v2. Configured with {@code endpointOverride} + path-style, it speaks to MinIO (On-Prem) or S3/GCS
 * (SaaS) identically — the S3 API is the abstraction, so there is no multi-cloud layer.
 */
public class S3StorageProvider implements StorageProvider {

  private final S3Client s3;
  private final String bucket;

  public S3StorageProvider(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, InputStream content, long contentLength, String contentType) {
    try {
      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
          RequestBody.fromInputStream(content, contentLength));
    } catch (S3Exception e) {
      throw new StorageException("Failed to store object " + key, e);
    }
  }

  @Override
  public Optional<StorageContent> get(String key) {
    try {
      ResponseInputStream<GetObjectResponse> stream =
          s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
      GetObjectResponse response = stream.response();
      return Optional.of(
          new StorageContent(stream, response.contentLength(), response.contentType()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      throw new StorageException("Failed to read object " + key, e);
    }
  }

  @Override
  public boolean exists(String key) {
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
      throw new StorageException("Failed to probe object " + key, e);
    }
  }

  @Override
  public boolean delete(String key) {
    if (!exists(key)) {
      return false;
    }
    try {
      s3.deleteObject(b -> b.bucket(bucket).key(key));
      return true;
    } catch (S3Exception e) {
      throw new StorageException("Failed to delete object " + key, e);
    }
  }
}
