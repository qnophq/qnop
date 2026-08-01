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
package io.qnop.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ValueValidator} across the setting value types. */
class ValueValidatorTest {

  @Test
  void acceptsValidTypedValues() {
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.GENERAL_APPLICATION_NAME, "anything"));
    assertDoesNotThrow(
        () ->
            ValueValidator.validate(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB, "42"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENABLED, "false"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENCRYPTION, "tls"));
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.TRACKING_PROVIDER, "matomo"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_PASSWORD, "p@ss"));
  }

  @Test
  void rejectsNonInteger() {
    assertThrows(
        SettingValidationException.class,
        () ->
            ValueValidator.validate(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB, "abc"));
  }

  @Test
  void rejectsNonBoolean() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENABLED, "yes"));
  }

  @Test
  void rejectsUnknownEnumOption() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.TRACKING_PROVIDER, "google-analytics"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENCRYPTION, "ssl"));
  }

  @Test
  void rejectsNull() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.GENERAL_BASE_URL, null));
  }

  @Test
  void enforcesIntegerRange() {
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_PORT, "587"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_PORT, "65535"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_PORT, "0"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_PORT, "70000"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB, "0"));
  }

  @Test
  void enforcesEmailFormatButAllowsBlank() {
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_FROM, "no-reply@example.com"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_FROM, ""));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_FROM, "not-an-email"));
  }

  @Test
  void enforcesHttpUrlFormatButAllowsBlank() {
    assertDoesNotThrow(
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.GENERAL_BASE_URL, "https://qnop.example"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.GENERAL_BASE_URL, ""));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.GENERAL_BASE_URL, "not a url"));
    assertThrows(
        SettingValidationException.class,
        () ->
            ValueValidator.validate(ApplicationSettingKey.GENERAL_BASE_URL, "ftp://qnop.example"));
  }

  @Test
  void boundsTheLengthOfBannerText() {
    assertDoesNotThrow(
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.BANNER_APP_TEXT, "Maintenance on Saturday 20:00 UTC."));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.BANNER_APP_TEXT, ""));
    // A banner is one line in a layout, not a place to publish a change log.
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.BANNER_APP_TEXT, "x".repeat(301)));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.BANNER_LOGIN_TEXT, "x".repeat(201)));
  }

  @Test
  void bannerLinksMustBeHttpUrls() {
    assertDoesNotThrow(
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.BANNER_LOGIN_LINK_URL, "https://qnop.example/demo"));
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.BANNER_LOGIN_LINK_URL, ""));
    // The banner is rendered as an anchor for every visitor of the sign-in page,
    // so the scheme check is the thing standing between an admin typo — or an
    // admin with bad intentions — and a script URL in everyone's browser.
    assertThrows(
        SettingValidationException.class,
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.BANNER_LOGIN_LINK_URL, "javascript:alert(1)"));
    assertThrows(
        SettingValidationException.class,
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.BANNER_APP_LINK_URL,
                "https://qnop.example/" + "x".repeat(500)));
  }

  @Test
  void enforcesTimezoneFormat() {
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE, "UTC"));
    assertDoesNotThrow(
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE, "Europe/Berlin"));
    assertThrows(
        SettingValidationException.class,
        () ->
            ValueValidator.validate(
                ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE, "Mars/Olympus_Mons"));
    assertThrows(
        SettingValidationException.class,
        () ->
            ValueValidator.validate(ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE, "not a zone"));
  }

  @Test
  void enforcesTimezoneFormatForPerUserSetting() {
    assertDoesNotThrow(() -> ValueValidator.validate(UserSettingKey.TIMEZONE, "UTC"));
    assertDoesNotThrow(() -> ValueValidator.validate(UserSettingKey.TIMEZONE, "Europe/Berlin"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(UserSettingKey.TIMEZONE, "not a zone"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(UserSettingKey.TIMEZONE, "Mars/Olympus_Mons"));
  }
}
