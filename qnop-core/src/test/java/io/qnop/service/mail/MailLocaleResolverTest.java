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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.UserSetting;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The recipient's mail language (issue #853): preference → application default → English. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailLocaleResolverTest {

  private static final UUID USER = UUID.randomUUID();
  private static final MailTemplateDefaults DEFAULTS = new MailTemplateDefaults();

  @Mock private UserSettingRepository userSettings;
  @Mock private ApplicationSettingsService settings;

  private MailLocaleResolver resolver() {
    return new MailLocaleResolver(userSettings, settings, DEFAULTS);
  }

  private void preferred(String value) {
    UserSetting setting = mock(UserSetting.class);
    when(setting.getSettingValue()).thenReturn(value);
    when(userSettings.findByUserIdAndSettingKey(USER, UserSettingKey.PREFERRED_LANGUAGE.getKey()))
        .thenReturn(Optional.of(setting));
  }

  private void applicationDefault(String value) {
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn(value);
  }

  @Test
  @DisplayName("the recipient's own preference wins")
  void preferenceFirst() {
    preferred("DE ");
    applicationDefault("en");

    assertThat(resolver().forUser(USER)).isEqualTo("de");
  }

  @Test
  @DisplayName("without a preference the application default applies")
  void defaultSecond() {
    when(userSettings.findByUserIdAndSettingKey(USER, UserSettingKey.PREFERRED_LANGUAGE.getKey()))
        .thenReturn(Optional.empty());
    applicationDefault("de");

    assertThat(resolver().forUser(USER)).isEqualTo("de");
    assertThat(resolver().defaultLanguage()).isEqualTo("de");
  }

  @Test
  @DisplayName("a blank preference is no preference")
  void blankPreferenceIsSkipped() {
    preferred("  ");
    applicationDefault("de");

    assertThat(resolver().forUser(USER)).isEqualTo("de");
  }

  @Test
  @DisplayName("with neither, mail is English")
  void englishLast() {
    preferred("");
    applicationDefault("");

    assertThat(resolver().forUser(USER)).isEqualTo("en");
    applicationDefault(null);
    assertThat(resolver().defaultLanguage()).isEqualTo("en");
  }

  @Test
  @DisplayName(
      "a preference mail is not shipped in falls to the default, so one mail is one language")
  void unshippedPreferenceFallsToTheDefault() {
    preferred("xx");
    applicationDefault("de");

    assertThat(resolver().forUser(USER)).isEqualTo("de");
  }

  @Test
  @DisplayName("the application default counts even where mail is not shipped in it")
  void applicationDefaultAlwaysCounts() {
    preferred("");
    applicationDefault("xx");

    // Its admin-edited rows are reachable; the template service falls back per template.
    assertThat(resolver().forUser(USER)).isEqualTo("xx");
  }

  @Test
  @DisplayName("an unknown user (no id yet) gets the application default")
  void noUserId() {
    applicationDefault("de");

    assertThat(resolver().forUser(null)).isEqualTo("de");
  }

  @Test
  @DisplayName("values are trimmed and lower-cased")
  void normalises() {
    assertThat(MailLocaleResolver.firstOf(null, " DE ")).isEqualTo("de");
    assertThat(MailLocaleResolver.firstOf()).isEqualTo("en");
  }
}
