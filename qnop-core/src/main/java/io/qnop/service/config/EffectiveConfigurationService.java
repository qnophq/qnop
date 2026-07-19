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

import io.qnop.api.v1.model.ConfigurationResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Assembles the effective {@code qnop.*} configuration served under {@code GET
 * /api/v1/admin/configuration} (issue #522). The Spring wiring lives here: it enumerates every
 * bound {@code @ConfigurationProperties} bean, keeps only the {@code qnop} namespace — so framework
 * beans such as {@code spring.datasource} (and the DB password) never enter the tree — and
 * delegates the flattening + secret redaction to the DB-free {@link ConfigurationTreeBuilder}.
 *
 * <p>Beans are ordered by prefix so the tree reads root-first: {@code qnop} before {@code
 * qnop.auth.rate-limit}, {@code qnop.s3}, and so on. Because the tree is grouped by the effective
 * property path, {@code qnop.auth.rate-limit.*} lands in the same {@code auth} group as the core
 * auth settings.
 */
@Service
public class EffectiveConfigurationService {

  private static final String QNOP_PREFIX = "qnop";

  /** Every module's compile-time metadata lands under this well-known classpath location. */
  private static final String METADATA_LOCATION =
      "classpath*:META-INF/spring-configuration-metadata.json";

  private final ApplicationContext context;
  private final ConfigurationTreeBuilder builder;

  /**
   * Property-path → description, loaded once at startup: the metadata is fixed for the running
   * artifact, so there is nothing to reload and one scan keeps the endpoint allocation-free.
   */
  private final Map<String, String> descriptions;

  public EffectiveConfigurationService(
      ApplicationContext context, ConfigurationTreeBuilder builder) {
    this.context = context;
    this.builder = builder;
    this.descriptions = ConfigurationMetadata.parse(loadMetadataDocuments(context));
  }

  /** The redacted, grouped snapshot of the effective {@code qnop.*} configuration. */
  public ConfigurationResponse effectiveConfiguration() {
    SequencedMap<String, Object> roots = new LinkedHashMap<>();
    context.getBeansWithAnnotation(ConfigurationProperties.class).values().stream()
        .map(bean -> new PrefixedBean(prefixOf(bean), bean))
        .filter(prefixed -> isQnopNamespace(prefixed.prefix()))
        .sorted(Comparator.comparing(PrefixedBean::prefix))
        .forEach(prefixed -> roots.put(prefixed.prefix(), prefixed.bean()));
    return builder.build(roots, descriptions);
  }

  /**
   * Reads every {@code spring-configuration-metadata.json} on the classpath (one per module that
   * declares {@code @ConfigurationProperties}) into a raw-JSON list for {@link
   * ConfigurationMetadata} to parse. A missing metadata resource is not an error — the page renders
   * without descriptions rather than failing.
   */
  private static List<String> loadMetadataDocuments(ApplicationContext context) {
    try {
      Resource[] resources =
          new PathMatchingResourcePatternResolver(context).getResources(METADATA_LOCATION);
      List<String> documents = new ArrayList<>(resources.length);
      for (Resource resource : resources) {
        try (var input = resource.getInputStream()) {
          documents.add(StreamUtils.copyToString(input, StandardCharsets.UTF_8));
        }
      }
      return documents;
    } catch (IOException scanFailed) {
      throw new UncheckedIOException("Could not read configuration metadata", scanFailed);
    }
  }

  private static boolean isQnopNamespace(String prefix) {
    return prefix != null && (prefix.equals(QNOP_PREFIX) || prefix.startsWith(QNOP_PREFIX + "."));
  }

  private static String prefixOf(Object bean) {
    ConfigurationProperties annotation =
        AnnotationUtils.findAnnotation(bean.getClass(), ConfigurationProperties.class);
    if (annotation == null) {
      return null;
    }
    return annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
  }

  private record PrefixedBean(String prefix, Object bean) {}
}
