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
package io.qnop.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

/**
 * The labels a dashboard panel or an alert rule relies on outside its selectors (issue #981): the
 * ones it groups by, the ones its legend prints and the ones its annotations print.
 *
 * <p>A selector on a label that does not exist matches nothing, and a panel shows "no data". A
 * grouping or a template on such a label fails more quietly: {@code sum by (job)} over a series
 * without {@code job} is one aggregate series, and {@code {{ $labels.job }}} prints nothing. Both
 * read as "nothing is wrong".
 *
 * <p>The files are parsed rather than searched, so that an alert expression spread over several
 * lines of YAML is one expression, and a legend belongs to the expression of its own panel.
 *
 * @param where the panel or rule, for the failure message
 * @param series the {@code qnop_*} series the expression reads
 * @param labels the labels the expression groups by, and its legend or annotations print
 */
record QueryLabelUsage(String where, Set<String> series, Set<String> labels) {

  /** A qnop series name anywhere in an expression, with or without a selector. */
  private static final Pattern QNOP_SERIES = Pattern.compile("\\b(qnop_[a-z0-9_]+)");

  /**
   * A clause that names labels: a grouping ({@code by (a, b)}, {@code without (a)}), before or
   * after its operand, or a vector matching ({@code on}, {@code ignoring}, {@code group_left},
   * {@code group_right}). A matching label must be on both sides, so on the qnop series, too.
   */
  private static final Pattern GROUPING =
      Pattern.compile("\\b(?:by|without|on|ignoring|group_left|group_right)\\s*\\(([^)]*)\\)");

  /** A quoted string in an expression: a label value, never a series name. */
  private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"");

  /** A label a Grafana legend prints: {@code {{label}}}. */
  private static final Pattern LEGEND_LABEL = Pattern.compile("\\{\\{\\s*([a-zA-Z_]\\w*)\\s*}}");

  /**
   * A label an alert annotation prints, in any of the forms Go templates allow: {@code
   * $labels.label}, {@code $labels["label"]} or {@code index $labels "label"}.
   */
  private static final Pattern TEMPLATE_LABEL =
      Pattern.compile(
          "\\$labels\\.([a-zA-Z_]\\w*)"
              + "|\\$labels\\[\\s*\"([^\"]+)\"\\s*]"
              + "|index\\s+\\$labels\\s+\"([^\"]+)\"");

  /** Every panel query of a Grafana dashboard, nested rows included. */
  static List<QueryLabelUsage> ofDashboard(String json) {
    Map<?, ?> dashboard = new ObjectMapper().readValue(json, Map.class);
    List<QueryLabelUsage> usages = new ArrayList<>();
    collectPanels(list(dashboard.get("panels")), usages);
    return usages;
  }

  /** Every rule of a Prometheus rule file. */
  static List<QueryLabelUsage> ofAlertRules(String yaml) {
    Map<?, ?> rules = new Yaml().load(yaml);
    List<QueryLabelUsage> usages = new ArrayList<>();
    for (Object group : list(rules.get("groups"))) {
      for (Object item : list(((Map<?, ?>) group).get("rules"))) {
        Map<?, ?> rule = (Map<?, ?>) item;
        String expr = text(rule.get("expr"));
        Set<String> labels = grouping(expr);
        Object annotations = rule.get("annotations");
        if (annotations instanceof Map<?, ?> texts) {
          texts.values().forEach(text -> labels.addAll(matches(TEMPLATE_LABEL, text)));
        }
        usages.add(new QueryLabelUsage("alert " + rule.get("alert"), series(expr), labels));
      }
    }
    return usages;
  }

  private static void collectPanels(List<?> panels, List<QueryLabelUsage> usages) {
    for (Object item : panels) {
      Map<?, ?> panel = (Map<?, ?>) item;
      for (Object target : list(panel.get("targets"))) {
        Map<?, ?> query = (Map<?, ?>) target;
        String expr = text(query.get("expr"));
        Set<String> labels = grouping(expr);
        labels.addAll(matches(LEGEND_LABEL, query.get("legendFormat")));
        usages.add(
            new QueryLabelUsage("panel \"" + panel.get("title") + "\"", series(expr), labels));
      }
      collectPanels(list(panel.get("panels")), usages);
    }
  }

  private static Set<String> series(String expr) {
    return new TreeSet<>(matches(QNOP_SERIES, QUOTED.matcher(expr).replaceAll("\"\"")));
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  private static Set<String> grouping(String expr) {
    Set<String> labels = new LinkedHashSet<>();
    for (String clause : matches(GROUPING, QUOTED.matcher(expr).replaceAll("\"\""))) {
      for (String label : clause.split(",")) {
        if (!label.isBlank()) {
          labels.add(label.strip());
        }
      }
    }
    return labels;
  }

  private static List<String> matches(Pattern pattern, Object text) {
    List<String> found = new ArrayList<>();
    if (text == null) {
      return found;
    }
    Matcher matcher = pattern.matcher(text.toString());
    while (matcher.find()) {
      // The first group that matched: a pattern may offer several forms of one thing.
      for (int group = 1; group <= matcher.groupCount(); group++) {
        if (matcher.group(group) != null) {
          found.add(matcher.group(group));
          break;
        }
      }
    }
    return found;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> items ? items : List.of();
  }
}
