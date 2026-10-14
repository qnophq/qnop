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

import io.qnop.entity.UserSetting;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Chooses the language a mail is written in (issue #853): the recipient's {@code
 * preferred_language}, else the application's {@code general.default_language}, else {@code en}.
 * Blank values are skipped. This mirrors the UI's resolution order (ADR-0063) without the device
 * step, which a mail has no device for.
 *
 * <p>As in the UI, a preference counts only if it names a language mail is shipped in ({@link
 * MailTemplateDefaults#locales()}); otherwise the recipient gets the application default. That
 * keeps one mail in one language — a Spanish preference on a German instance would otherwise get
 * the German template (the template fallback) around English phrases (the phrase fallback). The
 * application default always counts, shipped or not, so an instance whose admins customised the
 * templates in their own default language keeps sending those.
 *
 * <p>The one implementation every sender uses, so that the template and the server-rendered
 * variables of one mail are always resolved for the same language.
 */
@Component
public class MailLocaleResolver {

  /** The language of the built-in {@link MailTemplateKey} catalog and the last fallback. */
  public static final String FALLBACK_LOCALE = "en";

  private final UserSettingRepository userSettings;
  private final ApplicationSettingsService settings;
  private final MailTemplateDefaults defaults;

  public MailLocaleResolver(
      UserSettingRepository userSettings,
      ApplicationSettingsService settings,
      MailTemplateDefaults defaults) {
    this.userSettings = userSettings;
    this.settings = settings;
    this.defaults = defaults;
  }

  /** The mail language for the user with {@code userId}. */
  public String forUser(UUID userId) {
    String preferred =
        userId == null
            ? null
            : userSettings
                .findByUserIdAndSettingKey(userId, UserSettingKey.PREFERRED_LANGUAGE.getKey())
                .map(UserSetting::getSettingValue)
                .orElse(null);
    if (preferred != null && !preferred.isBlank()) {
      String language = firstOf(preferred);
      if (defaults.locales().contains(language)) {
        return language;
      }
    }
    return defaultLanguage();
  }

  /** The mail language for somebody without a stored preference: the application default. */
  public String defaultLanguage() {
    return firstOf(configuredDefault());
  }

  private String configuredDefault() {
    return settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE);
  }

  /**
   * The first candidate that normalizes to a language ({@link #normalize}), or {@link
   * #FALLBACK_LOCALE} when none does.
   */
  public static String firstOf(String... candidates) {
    for (String candidate : candidates) {
      String language = normalize(candidate);
      if (language != null) {
        return language;
      }
    }
    return FALLBACK_LOCALE;
  }

  /**
   * The one mail-language normalisation, as the UI's {@code toSupportedLocale} does it: trimmed,
   * reduced to the primary subtag and lower-cased — {@code de-AT}, {@code de_DE} and {@code DE} are
   * all {@code de}. {@code null} for a blank value. Used for stored preferences, the application
   * default and every locale the template service is handed, so a row is always stored and found
   * under the same key.
   */
  public static String normalize(String locale) {
    if (locale == null || locale.isBlank()) {
      return null;
    }
    String primary = locale.trim().split("[-_]", 2)[0].trim();
    return primary.isEmpty() ? null : primary.toLowerCase(Locale.ROOT);
  }
}
