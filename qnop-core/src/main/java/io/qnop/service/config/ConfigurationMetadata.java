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
package io.qnop.service.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses Spring Boot's {@code META-INF/spring-configuration-metadata.json} documents into a {@code
 * property-path → human description} map, used to caption the effective-configuration page (issue
 * #522). The metadata is generated at compile time by {@code spring-boot-configuration-processor}
 * from the Javadoc on the {@code @ConfigurationProperties} records, so the descriptions live next
 * to the properties themselves — a single source of truth that also feeds IDE autocomplete, with no
 * hand-kept text map to drift.
 *
 * <p>Deliberately Spring-free and DB-free (per the core guardrail): callers pass the raw JSON
 * documents, so this parser is exercised in plain unit tests. Discovering the classpath resources
 * is the Spring layer's job ({@link EffectiveConfigurationService}).
 *
 * <p>Property names in the metadata are already the canonical kebab-case paths ({@code
 * qnop.auth.jwt-secret}), matching {@link ConfigurationTreeBuilder}'s rendered paths, so the join
 * is a plain map lookup. Javadoc inline tags ({@code &#123;@code X&#125;}, {@code &#123;@link
 * X&#125;}) are unwrapped to their content so tooltips read as prose.
 */
public final class ConfigurationMetadata {

  /** A Javadoc inline tag such as {@code {@code X}} or {@code {@link X}} — captures the content. */
  private static final Pattern INLINE_TAG = Pattern.compile("\\{@\\w+\\s+([^}]*)}");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ConfigurationMetadata() {}

  /**
   * Merges the {@code properties[].description} of each metadata document into one {@code path →
   * description} lookup. A blank or malformed document is skipped; when the same path appears
   * twice, the first non-blank description wins (module iteration order), which is deterministic
   * and good enough — a property is documented in exactly one place.
   */
  public static Map<String, String> parse(Iterable<String> jsonDocuments) {
    Map<String, String> descriptions = new LinkedHashMap<>();
    for (String document : jsonDocuments) {
      if (document == null || document.isBlank()) {
        continue;
      }
      for (JsonNode property : readProperties(document)) {
        String name = property.path("name").asString(null);
        String description = property.path("description").asString(null);
        if (name == null || description == null || description.isBlank()) {
          continue;
        }
        descriptions.putIfAbsent(name, clean(description));
      }
    }
    return Map.copyOf(descriptions);
  }

  private static JsonNode readProperties(String document) {
    try {
      return MAPPER.readTree(document).path("properties");
    } catch (RuntimeException malformed) {
      // A metadata file we can't parse simply yields no descriptions — the page still renders.
      return MAPPER.createArrayNode();
    }
  }

  /** Unwrap Javadoc inline tags and collapse whitespace so the text reads as a plain sentence. */
  private static String clean(String description) {
    return INLINE_TAG.matcher(description).replaceAll("$1").replaceAll("\\s+", " ").trim();
  }
}
