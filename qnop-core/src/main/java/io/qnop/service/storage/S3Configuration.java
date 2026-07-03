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

import io.qnop.spi.storage.StorageProvider;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires the object-storage stack (issue #243, ADR-0005): an AWS SDK v2 {@link S3Client} built from
 * {@link S3Properties} (endpoint override + path-style for MinIO) and the Community {@link
 * StorageProvider} default. The provider is {@link ConditionalOnMissingBean} so a commercial add-on
 * (or a test) can substitute its own implementation (ADR-0002/0003).
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Configuration {

  @Bean
  S3Client s3Client(S3Properties properties) {
    var builder =
        S3Client.builder()
            .region(Region.of(properties.region()))
            .forcePathStyle(properties.pathStyleAccess())
            // Bound every call so a hung storage op cannot pin a worker indefinitely (issue #314).
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(properties.apiCallTimeout())
                    .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
                    .build())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
    if (properties.endpoint() != null) {
      builder.endpointOverride(URI.create(properties.endpoint()));
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean(StorageProvider.class)
  StorageProvider storageProvider(S3Client s3Client, S3Properties properties) {
    return new S3StorageProvider(s3Client, properties.bucket());
  }
}
