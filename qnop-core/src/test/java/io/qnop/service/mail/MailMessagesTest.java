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

import io.qnop.entity.NotificationType;
import io.qnop.entity.WorkflowState;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The server-rendered mail phrases (issue #853): English unchanged, every shipped language
 * complete, and the language never borrowed from the JVM's default locale.
 */
class MailMessagesTest {

  private static final MailMessages EN = MailMessages.forLocale("en");
  private static final MailMessages DE = MailMessages.forLocale("de");

  @Test
  @DisplayName("English phrases are byte-identical to the literals they replaced")
  void englishIsUnchanged() {
    assertThat(EN.footer()).isEqualTo("This is an automated message — please don't reply.");
    assertThat(EN.get("layout.cta.fallback")).isEqualTo("Open");
    assertThat(EN.get("layout.cta.linkHint"))
        .isEqualTo("Button not working? Paste this link into your browser:");
    assertThat(EN.decision("resolved")).isEqualTo("resolved");
    assertThat(EN.decision("dismissed")).isEqualTo("dismissed");
    assertThat(EN.decision("reopened")).isEqualTo("reopened");
    assertThat(EN.get("actor.participant")).isEqualTo("A participant");
    assertThat(EN.get("actor.system")).isEqualTo("System");
    assertThat(EN.get("actor.administrator")).isEqualTo("An administrator");
    assertThat(EN.get("actor.owner")).isEqualTo("The owner");
    assertThat(EN.get("digest.target.workspace")).isEqualTo("Your workspace");
    assertThat(EN.get("digest.target.unknownReview")).isEqualTo("A review you take part in");
  }

  @Test
  @DisplayName("English state names are exactly the old humanized constants")
  void englishStatesMatchTheHumanizedConstants() {
    for (WorkflowState state : WorkflowState.values()) {
      assertThat(EN.workflowState(state.name()))
          .as(state.name())
          .isEqualTo(MailMessages.humanize(state.name()));
    }
    assertThat(EN.workflowState("SOMETHING_NEW")).isEqualTo("Something new");
    assertThat(EN.workflowState(null)).isEmpty();
  }

  @Test
  @DisplayName("German renders the decision words, states and fallbacks in German")
  void germanPhrases() {
    assertThat(DE.locale()).isEqualTo("de");
    assertThat(DE.footer())
        .isEqualTo("Dies ist eine automatische Nachricht — bitte antworte nicht darauf.");
    assertThat(DE.decision("resolved")).isEqualTo("erledigt");
    assertThat(DE.decision("dismissed")).isEqualTo("verworfen");
    assertThat(DE.decision("reopened")).isEqualTo("wieder geöffnet");
    assertThat(DE.workflowState("CHANGES_REQUESTED")).isEqualTo("Änderungen angefordert");
    assertThat(DE.get("actor.participant")).isEqualTo("Eine beteiligte Person");
    // An unknown decision is passed through rather than lost.
    assertThat(DE.decision("decided")).isEqualTo("decided");
  }

  @Test
  @DisplayName("a language without a bundle, and the JVM default locale, never leak in")
  void fallsBackToEnglishNotToTheJvmLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertThat(MailMessages.forLocale("en").footer()).startsWith("This is an automated");
      assertThat(MailMessages.forLocale("xx").footer()).startsWith("This is an automated");
      assertThat(MailMessages.forLocale(null).locale()).isEqualTo("en");
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  @DisplayName("every notification type has a digest sentence and a count")
  void everyNotificationTypeIsWorded() {
    for (NotificationType type : NotificationType.values()) {
      assertThat(EN.has("digest.event." + type.name())).as(type.name()).isTrue();
      assertThat(EN.has("digest.count." + type.name())).as(type.name()).isTrue();
    }
  }

  @Test
  @DisplayName("every shipped mail language defines every phrase, and each one parses")
  void everyShippedLanguageIsComplete() throws IOException {
    Properties english = load("mail/messages.properties");
    Set<String> languages = new MailTemplateDefaults().locales();
    for (String language : languages) {
      if (language.equals("en")) {
        continue;
      }
      Properties translated = load("mail/messages_" + language + ".properties");
      assertThat(translated.stringPropertyNames())
          .as("phrases of %s", language)
          .containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
      for (String key : translated.stringPropertyNames()) {
        String value = translated.getProperty(key);
        assertThat(value).as("%s:%s", language, key).isNotBlank();
        if (value.contains("{")) {
          // Throws on a malformed pattern; formats with sample arguments.
          new MessageFormat(value, Locale.forLanguageTag(language)).format(new Object[] {2, "3"});
        }
      }
    }
  }

  private static Properties load(String resource) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = MailMessagesTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }

  /** A single quote that is not part of a doubled {@code ''} pair. */
  private static final java.util.regex.Pattern LONE_APOSTROPHE =
      java.util.regex.Pattern.compile("(?<!')'(?!')");

  @Test
  @DisplayName("no phrase that takes arguments has a lone apostrophe MessageFormat would swallow")
  void noLoneApostropheInFormattedPhrases() throws IOException {
    for (String language : new MailTemplateDefaults().locales()) {
      String resource =
          language.equals("en")
              ? "mail/messages.properties"
              : "mail/messages_" + language + ".properties";
      Properties phrases = load(resource);
      for (String key : phrases.stringPropertyNames()) {
        String value = phrases.getProperty(key);
        if (value.contains("{")) {
          assertThat(LONE_APOSTROPHE.matcher(value).find())
              .as("%s %s: double the apostrophe ('') in a formatted phrase", resource, key)
              .isFalse();
        }
      }
    }
  }
}
