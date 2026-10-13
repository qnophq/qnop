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
package io.qnop.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loading and validating the shipped per-language template defaults (issue #853). A bad resource
 * must fail the start, never a mail at send time — so each kind of mistake is pinned here.
 */
class MailTemplateDefaultsTest {

  private static String german;

  @BeforeAll
  static void readShippedGerman() throws IOException {
    try (InputStream in =
        MailTemplateDefaultsTest.class
            .getClassLoader()
            .getResourceAsStream("mail/templates/de.yaml")) {
      assertThat(in).isNotNull();
      german = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static MailTemplateDefaults load(String locale, String yaml) {
    return MailTemplateDefaults.fromYaml(Map.of(locale, yaml));
  }

  @Test
  @DisplayName("the classpath load finds German next to English")
  void classpathLoad() {
    MailTemplateDefaults defaults = new MailTemplateDefaults();

    assertThat(defaults.locales()).contains("en", "de");
    assertThat(defaults.find(MailTemplateKey.PASSWORD_RESET, "de"))
        .get()
        .extracting(MailTemplateContent::subject)
        .isEqualTo("Setze dein {{siteName}}-Passwort zurück");
  }

  @Test
  @DisplayName("English is the enum, an unshipped language is absent, findOrEnglish falls back")
  void lookups() {
    MailTemplateDefaults defaults = load("de", german);

    assertThat(defaults.find(MailTemplateKey.PASSWORD_RESET, "en"))
        .contains(MailTemplateContent.english(MailTemplateKey.PASSWORD_RESET));
    assertThat(defaults.find(MailTemplateKey.PASSWORD_RESET, "fr")).isEmpty();
    assertThat(defaults.findOrEnglish(MailTemplateKey.PASSWORD_RESET, "fr").locale())
        .isEqualTo("en");
    MailTemplateContent deleted = defaults.find(MailTemplateKey.REVIEW_DELETED, "de").orElseThrow();
    assertThat(deleted.locale()).isEqualTo("de");
    assertThat(deleted.ctaLabel()).isNull();
  }

  @Test
  @DisplayName("an unknown template key is rejected")
  void unknownKey() {
    String yaml =
        german
            + "\nreview.nonexistent:\n  subject: x\n  bodyPlain: x\n  bodyHtmlContent: x\n"
            + "  ctaLabel: x\n  preheader: x\n";

    assertThatThrownBy(() -> load("de", yaml))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown template key 'review.nonexistent'");
  }

  @Test
  @DisplayName("a missing template is rejected")
  void missingTemplate() {
    String yaml = german.substring(0, german.indexOf("review.mention:"));

    assertThatThrownBy(() -> load("de", yaml)).hasMessageContaining("'review.mention' is missing");
  }

  @Test
  @DisplayName("a missing field is rejected")
  void missingField() {
    String yaml = german.replace("  preheader: \"Setze dein {{siteName}}-Passwort zurück.\"\n", "");

    assertThatThrownBy(() -> load("de", yaml))
        .hasMessageContaining("auth.password_reset: 'preheader' is missing");
  }

  @Test
  @DisplayName("an unknown field is rejected")
  void unknownField() {
    String yaml =
        german.replace(
            "  ctaLabel: \"Neues Passwort wählen\"\n",
            "  ctaLabel: \"Neues Passwort wählen\"\n  footer: \"x\"\n");

    assertThatThrownBy(() -> load("de", yaml)).hasMessageContaining("unknown field 'footer'");
  }

  @Test
  @DisplayName("a placeholder outside the key's closed set is rejected")
  void unknownPlaceholder() {
    String yaml =
        german.replace(
            "subject: \"Setze dein {{siteName}}-Passwort zurück\"",
            "subject: \"Setze dein {{bogus}}-Passwort zurück\"");

    assertThatThrownBy(() -> load("de", yaml)).hasMessageContaining("unknown placeholders [bogus]");
  }

  @Test
  @DisplayName("a call to action English does not have is rejected, and a missing one too")
  void ctaLabelFollowsEnglish() {
    String extra =
        german.replace(
            "  preheader: \"„{{documentTitle}}“ wurde endgültig gelöscht.\"",
            "  ctaLabel: \"Öffnen\"\n  preheader: \"„{{documentTitle}}“ wurde endgültig gelöscht.\"");
    String missing = german.replace("  ctaLabel: \"Neues Passwort wählen\"\n", "");

    assertThatThrownBy(() -> load("de", extra)).hasMessageContaining("review.deleted: has no call");
    assertThatThrownBy(() -> load("de", missing))
        .hasMessageContaining("auth.password_reset: 'ctaLabel' is missing");
  }

  @Test
  @DisplayName("English cannot be shipped twice, and a file name must be a language code")
  void fileNames() {
    assertThatThrownBy(() -> load("en", german)).hasMessageContaining("English is defined");
    assertThatThrownBy(() -> load("deu", german)).hasMessageContaining("two-letter language code");
  }

  @Test
  @DisplayName("broken YAML and duplicate keys are rejected")
  void brokenYaml() {
    assertThatThrownBy(() -> load("de", "auth.password_reset: [")).hasMessageContaining("YAML");
    assertThatThrownBy(() -> load("de", german + "\n" + german.substring(german.indexOf("auth."))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> load("de", "- a list")).hasMessageContaining("expected a mapping");
  }
}
