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

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Object-storage configuration (issue #243, ADR-0005), bound from the {@code qnop.s3.*} namespace.
 * With {@code endpoint} set and {@code pathStyleAccess} on, the AWS SDK v2 client talks to a MinIO
 * (On-Prem) endpoint; leaving {@code endpoint} blank targets real AWS S3 (SaaS). The access/secret
 * keys are storage credentials (not crypto secrets), so they are only {@code @NotBlank} — MinIO's
 * local-dev keys are short by design.
 *
 * @param endpoint S3 endpoint override (e.g. {@code http://localhost:9000}); blank → real AWS S3
 * @param region the AWS region name (default {@code us-east-1}; MinIO ignores it but the SDK
 *     requires one)
 * @param bucket the bucket that holds all objects
 * @param accessKey S3 access key id
 * @param secretKey S3 secret access key
 * @param pathStyleAccess force path-style addressing (default true; required by MinIO)
 * @param autoCreateBucket create the bucket on startup if absent (default false; enable for local
 *     dev / tests against an empty MinIO — real S3 buckets are pre-provisioned)
 * @param reaperGracePeriod how long an uploaded-but-uncommitted object is kept before the orphan
 *     reaper deletes it (default 1h)
 * @param apiCallTimeout hard ceiling on a whole S3 call including retries (default 60s); bounds a
 *     hung extraction/serving job so it cannot pin a worker indefinitely (issue #314)
 * @param apiCallAttemptTimeout ceiling on a single HTTP attempt before the SDK retries it (default
 *     20s)
 */
@ConfigurationProperties(prefix = "qnop.s3")
@Validated
public record S3Properties(
    String endpoint,
    String region,
    @NotBlank String bucket,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    Boolean pathStyleAccess,
    Boolean autoCreateBucket,
    Duration reaperGracePeriod,
    Duration apiCallTimeout,
    Duration apiCallAttemptTimeout) {

  public S3Properties {
    endpoint = (endpoint == null || endpoint.isBlank()) ? null : endpoint.trim();
    region = (region == null || region.isBlank()) ? "us-east-1" : region;
    pathStyleAccess = pathStyleAccess == null ? Boolean.TRUE : pathStyleAccess;
    autoCreateBucket = autoCreateBucket == null ? Boolean.FALSE : autoCreateBucket;
    reaperGracePeriod = reaperGracePeriod == null ? Duration.ofHours(1) : reaperGracePeriod;
    apiCallTimeout = apiCallTimeout == null ? Duration.ofSeconds(60) : apiCallTimeout;
    apiCallAttemptTimeout =
        apiCallAttemptTimeout == null ? Duration.ofSeconds(20) : apiCallAttemptTimeout;
  }
}
