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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.qnop.repository.MailTemplateRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.limits.FeatureToggleProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * English mail output is unchanged by issue #853, byte for byte.
 *
 * <p>The golden files under {@code mail/golden-en/} were generated from the pre-#853 {@link
 * MailTemplateKey} and {@link EmailLayoutBuilder}, with the same variables as {@link
 * MailTemplateServiceTest}. Rendering every template in English today — subject, plain body,
 * branded HTML, and the editor's default HTML — must reproduce them exactly. An intended change to
 * an English template regenerates its golden file in the same change.
 */
@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnglishMailIdentityTest {

  private static final String ACTION_URL = "https://qnop.example/reset?token=abc";

  static final Map<String, Object> FLOW_VARS =
      Map.ofEntries(
          Map.entry("siteName", "qnop"),
          Map.entry("recipientName", "Jane"),
          Map.entry("actionUrl", ACTION_URL),
          Map.entry("expiresAtHuman", "in 30 minutes"),
          Map.entry("actorName", "Alex Reviewer"),
          Map.entry("documentTitle", "Q3 contract draft"),
          Map.entry("annotationExcerpt", "The liability clause needs a cap."),
          Map.entry("commentExcerpt", "Agreed — let's cap it at 12 months."),
          Map.entry("decision", "resolved"),
          Map.entry("versionNumber", "3"),
          Map.entry("oldState", "In review"),
          Map.entry("newState", "Changes requested"),
          Map.entry("totalCount", 11),
          Map.entry("totalPhrase", "11 updates"),
          Map.entry("digestBody", "* Q3 contract draft — 3 new annotations, 7 comments"),
          Map.entry(
              "digestBodyHtml",
              "<ul><li><strong>Q3 contract draft</strong> — 3 new annotations, 7 comments</li></ul>"));

  @Mock private MailTemplateRepository repository;
  @Mock private UserRepository userRepository;
  @Mock private ApplicationSettingsService settings;

  private MailTemplateService service;

  @BeforeEach
  void setUp() {
    service =
        new MailTemplateService(
            repository,
            userRepository,
            settings,
            new EmailLayoutBuilder(),
            FeatureToggleProperties.all(),
            new MailTemplateDefaults());
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn("en");
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());
  }

  @ParameterizedTest
  @EnumSource(MailTemplateKey.class)
  @DisplayName("every template renders in English exactly as before")
  void englishIsByteIdentical(MailTemplateKey key) throws IOException {
    Map<String, String> golden = golden(key);

    RenderedMail mail = service.render(key, FLOW_VARS, "en");
    // A request without a language resolves to the (English) default the same way.
    RenderedMail unspecified = service.render(key, FLOW_VARS, null);
    MailTemplateView view = service.getEffective(key, "en");

    assertThat(mail.subject()).isEqualTo(golden.get("SUBJECT"));
    assertThat(mail.bodyPlain()).isEqualTo(golden.get("PLAIN"));
    assertThat(mail.bodyHtml()).isEqualTo(golden.get("HTML"));
    assertThat(unspecified).isEqualTo(mail);
    assertThat(view.defaultBodyHtml()).isEqualTo(golden.get("DEFAULT_HTML"));
    assertThat(view.defaultSubject()).isEqualTo(key.defaultSubject());
    assertThat(view.defaultBodyPlain()).isEqualTo(key.defaultBodyPlain());
  }

  /** The marker-delimited parts of a golden file; the comment header is skipped. */
  private static Map<String, String> golden(MailTemplateKey key) throws IOException {
    String resource = "mail/golden-en/" + key.key() + ".txt";
    String text;
    try (InputStream in =
        EnglishMailIdentityTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Map<String, String> parts = new LinkedHashMap<>();
    String[] names = {"SUBJECT", "PLAIN", "HTML", "DEFAULT_HTML"};
    for (int i = 0; i < names.length; i++) {
      String marker = "=====" + names[i] + "=====\n";
      int start = text.indexOf(marker) + marker.length();
      int end =
          i + 1 < names.length ? text.indexOf("=====" + names[i + 1] + "=====\n") : text.length();
      // Each part is followed by exactly one newline of the file's own.
      parts.put(names[i], text.substring(start, end - 1));
    }
    return parts;
  }
}
