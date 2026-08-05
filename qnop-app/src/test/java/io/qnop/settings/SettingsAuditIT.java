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
package io.qnop.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.entity.AuditEvent;
import io.qnop.repository.AuditEventRepository;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Application settings leave a trail (issue #718).
 *
 * <p>Written because "when did this deployment start forwarding full IP addresses, and who decided"
 * was not answerable from qnop — the actor was passed to the write and then dropped.
 */
class SettingsAuditIT extends SeededIntegrationTest {

  private static final String EVENT = "settings.updated";

  @Autowired private ApplicationSettingsService settings;
  @Autowired private AuditEventRepository auditEvents;

  /**
   * Settings are shared state that outlives a test: the row stays written and the service caches
   * it, so a case that renames the instance renames it for every test that runs afterwards. This
   * suite therefore puts back what it touched — the seeded values.
   */
  @org.junit.jupiter.api.AfterEach
  void restoreTouchedSettings() {
    settings.update(Map.of("general.application_name", "qnop", "smtp.password", ""), ADMIN_ID);
  }

  @Test
  @DisplayName("a change records the setting, both values and who made it")
  void recordsTheChange() {
    settings.update(Map.of("general.application_name", "Audited qnop"), ADMIN_ID);

    AuditEvent event = latest();
    assertThat(event.getActorId()).isEqualTo(ADMIN_ID);
    // Parsed, not string-matched: the payload is JSON, and asserting on its shape
    // survives a formatting change that a substring search would not.
    var detail = parse(event.getDetail());
    assertThat(detail.get("key").asString()).isEqualTo("general.application_name");
    assertThat(detail.get("next").asString()).isEqualTo("Audited qnop");
    assertThat(detail.get("secret").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("a secret is recorded as changed, with neither value")
  void neverWritesSecrets() {
    // The snapshot holds decrypted secrets, so a careless payload here would make
    // the audit page the least protected copy of the SMTP password in the system.
    settings.update(Map.of("smtp.password", "hunter2-and-then-some"), ADMIN_ID);

    AuditEvent event = latest();
    var detail = parse(event.getDetail());
    assertThat(detail.get("key").asString()).isEqualTo("smtp.password");
    assertThat(detail.get("secret").asBoolean()).isTrue();
    assertThat(detail.has("previous")).isFalse();
    assertThat(detail.has("next")).isFalse();
    assertThat(event.getDetail()).doesNotContain("hunter2");
  }

  @Test
  @DisplayName("re-saving an unchanged value records nothing")
  void ignoresNoOps() {
    settings.update(Map.of("general.application_name", "Same name"), ADMIN_ID);
    long after = count();

    settings.update(Map.of("general.application_name", "Same name"), ADMIN_ID);

    // Saving the settings form re-sends every field it holds; an audit that logs a
    // hundred no-ops per visit buries the entry somebody came to find.
    assertThat(count()).isEqualTo(after);
  }

  @Test
  @DisplayName("a value containing quotes does not break the payload")
  void escapesValues() {
    // The scheduler concatenates its detail JSON and gets away with it because it
    // only ever writes booleans and known ids. A setting value is arbitrary text.
    String awkward = "He said \"hello\" — {not json}";
    settings.update(Map.of("general.application_name", awkward), ADMIN_ID);

    // The scheduler concatenates its detail JSON and gets away with it because it
    // only writes booleans and known ids. A setting value is arbitrary text, so a
    // quote in it would have produced a payload nothing can read back.
    assertThat(parse(latest().getDetail()).get("next").asString()).isEqualTo(awkward);
  }

  private AuditEvent latest() {
    List<AuditEvent> events =
        auditEvents.findAll().stream()
            .filter(event -> EVENT.equals(event.getEventType()))
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .toList();
    assertThat(events).as("a settings.updated event").isNotEmpty();
    return events.get(0);
  }

  private long count() {
    return auditEvents.findAll().stream().filter(e -> EVENT.equals(e.getEventType())).count();
  }

  private tools.jackson.databind.JsonNode parse(String json) {
    return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
  }
}
