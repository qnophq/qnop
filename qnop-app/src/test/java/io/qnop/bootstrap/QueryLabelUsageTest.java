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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The parsing behind {@link MetricCatalogueTest}'s label check (issue #981). */
class QueryLabelUsageTest {

  @Test
  @DisplayName("every panel query is read, nested rows included, with its legend")
  void readsDashboardPanelsAndLegends() {
    String dashboard =
        """
        {"panels": [
          {"title": "Top", "targets": [
            {"expr": "sum by(outcome) (rate(qnop_mail_send_seconds_count[5m]))",
             "legendFormat": "{{outcome}} / {{ template }}"}]},
          {"title": "Row", "type": "row", "panels": [
            {"title": "Nested", "targets": [
              {"expr": "max(qnop_jobs{state=\\"pending\\"})"}]}]},
          {"title": "Framework", "targets": [
            {"expr": "sum by (uri) (rate(http_server_requests_seconds_count[5m]))"}]},
          {"title": "Text"}
        ]}
        """;

    List<QueryLabelUsage> usages = QueryLabelUsage.ofDashboard(dashboard);

    assertThat(usages)
        .extracting(QueryLabelUsage::where)
        .containsExactly("panel \"Top\"", "panel \"Nested\"", "panel \"Framework\"");
    assertThat(usages.get(0).series()).containsExactly("qnop_mail_send_seconds_count");
    assertThat(usages.get(0).labels()).containsExactlyInAnyOrder("outcome", "template");
    assertThat(usages.get(1).series()).containsExactly("qnop_jobs");
    assertThat(usages.get(1).labels()).isEmpty();
    // Framework series only: no qnop series, so the catalogue check leaves it alone.
    assertThat(usages.get(2).series()).isEmpty();
  }

  @Test
  @DisplayName("vector matching clauses name labels, too")
  void readsVectorMatchingLabels() {
    String rules =
        """
        groups:
          - name: test
            rules:
              - alert: Matched
                expr: |
                  max(qnop_jobs) == on(job) group_left(state, type)
                    max without(trigger) (qnop_scheduler_runs_total) unless ignoring (scope) qnop_info
        """;

    assertThat(QueryLabelUsage.ofAlertRules(rules))
        .singleElement()
        .extracting(QueryLabelUsage::labels)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
        .containsExactlyInAnyOrder("job", "state", "type", "trigger", "scope");
  }

  @Test
  @DisplayName("every form of $labels an annotation can use is read")
  void readsEveryTemplateForm() {
    String rules =
        """
        groups:
          - name: test
            rules:
              - alert: Templated
                expr: max(qnop_jobs) > 0
                annotations:
                  summary: '{{ $labels.state }} {{$labels.type}} {{ $labels["outcome"] }}'
                  description: '{{ index $labels "scheduler_job" }} is {{ $value }}'
        """;

    assertThat(QueryLabelUsage.ofAlertRules(rules))
        .singleElement()
        .extracting(QueryLabelUsage::labels)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
        .containsExactlyInAnyOrder("state", "type", "outcome", "scheduler_job");
  }

  @Test
  @DisplayName("a quoted label value is neither a series nor a grouping")
  void ignoresQuotedValues() {
    String rules =
        """
        groups:
          - name: test
            rules:
              - alert: Quoted
                expr: max(qnop_jobs{type=~"qnop_extract|by (job)"}) > 0
              - alert: Empty
        """;

    List<QueryLabelUsage> usages = QueryLabelUsage.ofAlertRules(rules);

    assertThat(usages.get(0).series()).containsExactly("qnop_jobs");
    assertThat(usages.get(0).labels()).isEmpty();
    // A rule without an expression reads nothing, rather than the string "null".
    assertThat(usages.get(1).series()).isEmpty();
    assertThat(usages.get(1).labels()).isEmpty();
  }
}
