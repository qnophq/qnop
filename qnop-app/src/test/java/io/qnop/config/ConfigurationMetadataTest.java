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
package io.qnop.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.config.ConfigurationMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the configuration-metadata parser (issue #522): the same {@code
 * spring-configuration-metadata.json} shape the {@code spring-boot-configuration-processor} emits,
 * driven directly so the path→description join, Javadoc inline-tag cleanup, and the skip rules are
 * pinned without needing the live classpath scan.
 */
class ConfigurationMetadataTest {

  @Test
  void mapsPropertyNameToDescriptionAndUnwrapsJavadocInlineTags() {
    String document =
        """
        { "properties": [
          { "name": "qnop.auth.jwt-secret", "type": "java.lang.String",
            "description": "HKDF input keying material; must be a strong secret" },
          { "name": "qnop.auth.issuer", "type": "java.lang.String",
            "description": "the {@code iss} claim of self-issued tokens (default {@code qnop})" }
        ] }
        """;

    Map<String, String> descriptions = ConfigurationMetadata.parse(List.of(document));

    assertThat(descriptions.get("qnop.auth.jwt-secret"))
        .isEqualTo("HKDF input keying material; must be a strong secret");
    // {@code iss} and {@code qnop} are unwrapped to their bare content for prose tooltips.
    assertThat(descriptions.get("qnop.auth.issuer"))
        .isEqualTo("the iss claim of self-issued tokens (default qnop)");
  }

  @Test
  void skipsEntriesWithoutADescriptionAndBlankOrMalformedDocuments() {
    String withoutDescription =
        """
        { "properties": [ { "name": "qnop.s3.bucket", "type": "java.lang.String" } ] }
        """;

    Map<String, String> descriptions =
        ConfigurationMetadata.parse(List.of(withoutDescription, "   ", "not json at all", ""));

    assertThat(descriptions).doesNotContainKey("qnop.s3.bucket").isEmpty();
  }

  @Test
  void firstNonBlankDescriptionWinsWhenAPathIsDocumentedTwice() {
    String first =
        """
        { "properties": [ { "name": "qnop.s3.region", "description": "the AWS region name" } ] }
        """;
    String second =
        """
        { "properties": [ { "name": "qnop.s3.region", "description": "a later duplicate" } ] }
        """;

    Map<String, String> descriptions = ConfigurationMetadata.parse(List.of(first, second));

    assertThat(descriptions.get("qnop.s3.region")).isEqualTo("the AWS region name");
  }
}
