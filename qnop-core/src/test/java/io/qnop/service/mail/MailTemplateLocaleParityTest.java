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

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every shipped mail language mirrors English (issue #853), as the UI's catalog parity test does
 * for its catalogs: each template exists with every field, a call to action exactly where English
 * has one, and — part by part — exactly the placeholders English uses. Equality rather than a
 * subset: a translation that drops {@code {{expiresAtHuman}}} would silently hide the expiry.
 */
class MailTemplateLocaleParityTest {

  /** Every Mustache tag, triple-staches included (the digest's pre-rendered HTML block). */
  private static final Pattern TAG = Pattern.compile("\\{\\{\\{?\\s*([^{}]+?)\\s*}?}}");

  private static final MailTemplateDefaults DEFAULTS = new MailTemplateDefaults();

  @Test
  @DisplayName("German is shipped (the first non-English mail language)")
  void germanIsShipped() {
    assertThat(DEFAULTS.locales()).contains("de");
  }

  @Test
  @DisplayName("every shipped language has every template, field and placeholder English has")
  void everyLanguageMirrorsEnglish() {
    SoftAssertions soft = new SoftAssertions();
    for (String locale : DEFAULTS.locales()) {
      for (MailTemplateKey key : MailTemplateKey.values()) {
        MailTemplateContent english = MailTemplateContent.english(key);
        MailTemplateContent translated = DEFAULTS.find(key, locale).orElse(null);
        soft.assertThat(translated).as("%s %s", locale, key.key()).isNotNull();
        if (translated == null) {
          continue;
        }
        soft.assertThat(translated.ctaLabel() == null)
            .as("%s %s has a CTA exactly where English has one", locale, key.key())
            .isEqualTo(english.ctaLabel() == null);
        compare(soft, locale, key, "subject", english, translated, MailTemplateContent::subject);
        compare(
            soft, locale, key, "bodyPlain", english, translated, MailTemplateContent::bodyPlain);
        compare(
            soft,
            locale,
            key,
            "bodyHtmlContent",
            english,
            translated,
            MailTemplateContent::bodyHtmlContent);
        compare(
            soft, locale, key, "preheader", english, translated, MailTemplateContent::preheader);
      }
    }
    soft.assertAll();
  }

  private static void compare(
      SoftAssertions soft,
      String locale,
      MailTemplateKey key,
      String field,
      MailTemplateContent english,
      MailTemplateContent translated,
      Function<MailTemplateContent, String> part) {
    soft.assertThat(tags(part.apply(translated)))
        .as("%s %s %s placeholders", locale, key.key(), field)
        .isEqualTo(tags(part.apply(english)));
  }

  private static Set<String> tags(String content) {
    Set<String> tags = new TreeSet<>();
    Matcher matcher = TAG.matcher(content);
    while (matcher.find()) {
      tags.add(matcher.group());
    }
    return tags;
  }
}
