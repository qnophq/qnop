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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.http.HttpClientProperties;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@ExtendWith(MockitoExtension.class)
class MailSenderProviderTest {

  @Mock private ApplicationSettingsService settings;

  private MailSenderProvider provider;

  @BeforeEach
  void setUp() {
    // Explicit non-default timeouts prove the transport picks them up from config.
    HttpClientProperties httpClient =
        new HttpClientProperties(
            null, null, Duration.ofSeconds(8), Duration.ofSeconds(24), Duration.ofSeconds(42));
    provider = new MailSenderProvider(settings, httpClient);
  }

  private void configureSmtp() {
    configureSmtp("starttls");
  }

  private void configureSmtp(String encryption) {
    lenient().when(settings.getBoolean(ApplicationSettingKey.SMTP_ENABLED)).thenReturn(true);
    lenient()
        .when(settings.getString(ApplicationSettingKey.SMTP_HOST))
        .thenReturn("smtp.example.com");
    lenient().when(settings.getInteger(ApplicationSettingKey.SMTP_PORT)).thenReturn(587);
    lenient().when(settings.getString(ApplicationSettingKey.SMTP_USERNAME)).thenReturn("user");
    lenient().when(settings.getString(ApplicationSettingKey.SMTP_PASSWORD)).thenReturn("secret");
    lenient()
        .when(settings.getString(ApplicationSettingKey.SMTP_ENCRYPTION))
        .thenReturn(encryption);
  }

  private static Properties propsOf(JavaMailSender sender) {
    return ((JavaMailSenderImpl) sender).getJavaMailProperties();
  }

  @Test
  @DisplayName("current() is null and isEnabled() false when the master switch is off")
  void disabledWhenMasterSwitchOff() {
    when(settings.getBoolean(ApplicationSettingKey.SMTP_ENABLED)).thenReturn(false);

    assertThat(provider.isEnabled()).isFalse();
    assertThat(provider.current()).isNull();
  }

  @Test
  @DisplayName("current() is null and isEnabled() false when the host is blank")
  void disabledWhenHostBlank() {
    when(settings.getBoolean(ApplicationSettingKey.SMTP_ENABLED)).thenReturn(true);
    when(settings.getString(ApplicationSettingKey.SMTP_HOST)).thenReturn("");

    assertThat(provider.isEnabled()).isFalse();
    assertThat(provider.current()).isNull();
  }

  @Test
  @DisplayName("builds and caches a JavaMailSender from the SMTP settings")
  void buildsAndCachesSender() {
    configureSmtp();

    JavaMailSender first = provider.current();

    assertThat(first).isInstanceOf(JavaMailSenderImpl.class);
    assertThat(((JavaMailSenderImpl) first).getHost()).isEqualTo("smtp.example.com");
    assertThat(((JavaMailSenderImpl) first).getPort()).isEqualTo(587);
    assertThat(provider.current()).isSameAs(first); // cached
  }

  @Test
  @DisplayName("encryption=starttls requires STARTTLS (no implicit SSL, no silent downgrade)")
  void encryptionStartTls() {
    configureSmtp("starttls");

    Properties props = propsOf(provider.current());

    assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
  }

  @Test
  @DisplayName("encryption=tls enables implicit SSL (port 465) and no STARTTLS")
  void encryptionImplicitTls() {
    configureSmtp("tls");

    Properties props = propsOf(provider.current());

    assertThat(props.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.starttls.enable")).isNull();
  }

  @Test
  @DisplayName("the transport carries the configured connect/read/write timeouts (issue #342)")
  void transportHasTimeouts() {
    configureSmtp();

    Properties props = propsOf(provider.current());

    assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("8000");
    assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("24000");
    assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("42000");
  }

  @Test
  @DisplayName("encryption=none leaves the connection plaintext")
  void encryptionNone() {
    configureSmtp("none");

    Properties props = propsOf(provider.current());

    assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
    assertThat(props.getProperty("mail.smtp.starttls.enable")).isNull();
  }

  @Test
  @DisplayName("an unrecognized encryption value falls back to required STARTTLS")
  void encryptionUnknownFallsBackToStartTls() {
    configureSmtp("ssl"); // not a valid enum option; only reachable via out-of-band DB edits

    Properties props = propsOf(provider.current());

    assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
  }

  @Test
  @DisplayName("invalidates the cached sender when an SMTP setting changes")
  void invalidatesOnSmtpChange() {
    configureSmtp();
    JavaMailSender first = provider.current();

    provider.onSettingsChanged(EnumSet.of(ApplicationSettingKey.SMTP_HOST));

    assertThat(provider.current()).isNotSameAs(first);
  }

  @Test
  @DisplayName("ignores changes to non-SMTP settings")
  void ignoresUnrelatedChange() {
    configureSmtp();
    JavaMailSender first = provider.current();

    provider.onSettingsChanged(EnumSet.of(ApplicationSettingKey.GENERAL_APPLICATION_NAME));

    assertThat(provider.current()).isSameAs(first);
  }
}
