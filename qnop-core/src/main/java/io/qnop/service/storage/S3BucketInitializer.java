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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Ensures the configured bucket exists on startup when {@code qnop.s3.auto-create-bucket} is on
 * (local dev / tests against an empty MinIO). Real S3 buckets are pre-provisioned, so the default
 * is off and this is a no-op — the app never tries to create a bucket it may lack permission for.
 */
@Component
public class S3BucketInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(S3BucketInitializer.class);

  private final S3Client s3;
  private final S3Properties properties;

  public S3BucketInitializer(S3Client s3, S3Properties properties) {
    this.s3 = s3;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.autoCreateBucket()) {
      return;
    }
    String bucket = properties.bucket();
    try {
      s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      log.info("Object-storage bucket '{}' is present.", bucket);
    } catch (NoSuchBucketException e) {
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      log.info("Created object-storage bucket '{}'.", bucket);
    }
  }
}
