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

import io.qnop.api.v1.model.ConfigurationEntry;
import io.qnop.api.v1.model.ConfigurationGroup;
import io.qnop.api.v1.model.ConfigurationResponse;
import io.qnop.api.v1.model.ConfigurationValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the bound {@code @ConfigurationProperties} beans into the redacted, grouped
 * effective-config tree served under {@code GET /api/v1/admin/configuration} (issue #522).
 * Deliberately DB-free and Spring-free logic (per the core guardrail): the Spring wiring —
 * enumerating the beans and their prefixes — lives in {@link EffectiveConfigurationService}, so
 * this builder is exercised in plain unit tests by passing the property records directly.
 *
 * <p>Each bean is serialized through a dedicated {@link JsonMapper} configured for {@code
 * kebab-case} names (so the rendered paths match the yaml an operator edits) and ISO-8601 durations
 * (so {@code PT15M} shows instead of a raw nanosecond count). The tree is then flattened to leaves,
 * grouped by the first path segment after {@code qnop}, in first-seen order.
 *
 * <p><strong>Secret redaction, by construction.</strong> A leaf whose terminal path token is {@code
 * secret}, {@code password}, {@code key}, {@code token} or {@code salt} is emitted as a {@link
 * ConfigurationValueType#SECRET} entry carrying only a {@code configured} flag — the value never
 * enters the response. Terminal-token matching avoids false positives ({@code key-prefix}, {@code
 * password-change-required}); {@link #SECRET_ALLOW_LIST} documents deliberate exemptions.
 */
@Component
public class ConfigurationTreeBuilder {

  /** Terminal path tokens that mark a leaf as secret and force redaction. */
  private static final Set<String> SECRET_TOKENS =
      Set.of("secret", "password", "key", "token", "salt");

  /**
   * Full property paths that look secret by their terminal token but are deliberately safe to show.
   * Empty today; a future non-sensitive {@code *-key} / {@code *-token} property is exempted here
   * rather than by weakening the terminal-token rule.
   */
  private static final Set<String> SECRET_ALLOW_LIST = Set.of();

  private final JsonMapper mapper =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
          .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * Builds the grouped, redacted effective-config response.
   *
   * @param rootsByPrefix each bound properties bean keyed by its {@code @ConfigurationProperties}
   *     prefix (e.g. {@code qnop}, {@code qnop.s3}); iteration order sets the within-group entry
   *     order, and the first-seen group order.
   */
  public ConfigurationResponse build(SequencedMap<String, Object> rootsByPrefix) {
    List<ConfigurationEntry> entries = new ArrayList<>();
    for (Map.Entry<String, Object> root : rootsByPrefix.entrySet()) {
      JsonNode node = mapper.valueToTree(root.getValue());
      walk(root.getKey(), node, entries);
    }
    return group(entries);
  }

  /** Depth-first flatten: objects recurse, arrays become one list leaf, scalars become one leaf. */
  private void walk(String path, JsonNode node, List<ConfigurationEntry> out) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        walk(path + "." + field.getKey(), field.getValue(), out);
      }
    } else {
      out.add(leaf(path, node));
    }
  }

  private ConfigurationEntry leaf(String path, JsonNode node) {
    ConfigurationEntry entry = new ConfigurationEntry().path(path).envVar(toEnvVar(path));
    if (isSecret(path)) {
      boolean configured = !node.isNull() && !node.asString().isBlank();
      return entry.valueType(ConfigurationValueType.SECRET).value(null).configured(configured);
    }
    if (node.isNull()) {
      return entry.valueType(ConfigurationValueType.UNSET).value(null);
    }
    if (node.isArray()) {
      List<String> items = new ArrayList<>();
      node.forEach(element -> items.add(element.asString()));
      return entry.valueType(ConfigurationValueType.LIST).value(String.join(", ", items));
    }
    if (node.isBoolean()) {
      return entry.valueType(ConfigurationValueType.BOOLEAN).value(node.asString());
    }
    if (node.isNumber()) {
      return entry.valueType(ConfigurationValueType.NUMBER).value(node.asString());
    }
    String text = node.asString();
    ConfigurationValueType type =
        isDuration(text) ? ConfigurationValueType.DURATION : ConfigurationValueType.STRING;
    return entry.valueType(type).value(text);
  }

  private ConfigurationResponse group(List<ConfigurationEntry> entries) {
    SequencedMap<String, List<ConfigurationEntry>> byGroup = new LinkedHashMap<>();
    for (ConfigurationEntry entry : entries) {
      byGroup.computeIfAbsent(groupKey(entry.getPath()), unused -> new ArrayList<>()).add(entry);
    }
    List<ConfigurationGroup> groups = new ArrayList<>();
    byGroup.forEach(
        (key, groupEntries) -> groups.add(new ConfigurationGroup().key(key).entries(groupEntries)));
    return new ConfigurationResponse().groups(groups);
  }

  /** The top-level namespace: the first path segment after the {@code qnop} root. */
  private static String groupKey(String path) {
    String[] segments = path.split("\\.");
    return segments.length > 1 ? segments[1] : segments[0];
  }

  private static boolean isSecret(String path) {
    if (SECRET_ALLOW_LIST.contains(path)) {
      return false;
    }
    // The terminal *name token*: the last word of the last path segment, so
    // `qnop.auth.jwt-secret` matches on `secret` while `qnop.auth.oidc.frontend-base-url` (url) and
    // a hypothetical `key-prefix` (prefix) do not.
    int lastDot = path.lastIndexOf('.');
    String lastSegment = lastDot < 0 ? path : path.substring(lastDot + 1);
    int lastDash = lastSegment.lastIndexOf('-');
    String terminal = lastDash < 0 ? lastSegment : lastSegment.substring(lastDash + 1);
    return SECRET_TOKENS.contains(terminal);
  }

  /** Boot's relaxed-binding convention: uppercase, dots and dashes to underscores (see docs). */
  private static String toEnvVar(String path) {
    return path.toUpperCase().replace('.', '_').replace('-', '_');
  }

  /** ISO-8601 duration/period marker — {@code PT15M}, {@code PT168H}, {@code P7D}. */
  private static boolean isDuration(String text) {
    return text.matches("^P(T[0-9].*|[0-9].*)");
  }
}
