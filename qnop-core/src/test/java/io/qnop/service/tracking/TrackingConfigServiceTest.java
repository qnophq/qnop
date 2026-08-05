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
package io.qnop.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resolving the settings into a working configuration, or into nothing (issue #666).
 *
 * <p>"Nothing" is most of what is asserted here. Half-configured measurement is the state an
 * operator actually reaches — switch it on, then go and find the site id — and it has to be silent
 * rather than half-working.
 */
class TrackingConfigServiceTest {

  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);

  private TrackingConfigService service(boolean allowPrivateHost) {
    return new TrackingConfigService(settings, allowPrivateHost);
  }

  @BeforeEach
  void enabledPlausibleCloud() {
    when(settings.getBoolean(ApplicationSettingKey.TRACKING_ENABLED)).thenReturn(true);
    when(settings.getString(ApplicationSettingKey.TRACKING_PROVIDER)).thenReturn("plausible");
    when(settings.getString(ApplicationSettingKey.TRACKING_SITE_ID)).thenReturn("qnop.example");
    when(settings.getString(ApplicationSettingKey.TRACKING_HOST)).thenReturn("");
    when(settings.getString(ApplicationSettingKey.TRACKING_FORWARD_CLIENT_IP))
        .thenReturn("anonymized");
    when(settings.getBoolean(ApplicationSettingKey.TRACKING_CONSENT_REQUIRED)).thenReturn(true);
    when(settings.getBoolean(ApplicationSettingKey.TRACKING_RESPECT_DNT)).thenReturn(true);
    when(settings.getBoolean(ApplicationSettingKey.TRACKING_PRIVILEGED_ROLES)).thenReturn(false);
  }

  @Test
  @DisplayName("a hosted backend needs no host of its own")
  void resolvesCloudDefaults() {
    assertThat(service(false).current())
        .hasValueSatisfying(
            config -> {
              assertThat(config.provider()).isEqualTo(TrackingProvider.PLAUSIBLE);
              assertThat(config.scriptUrl()).isEqualTo("https://plausible.io/js/script.manual.js");
              assertThat(config.collectBaseUrl()).isEqualTo("https://plausible.io");
              assertThat(config.forwardClientIp()).isEqualTo(ClientIpForwarding.ANONYMIZED);
            });
  }

  @Test
  @DisplayName("switched off resolves to nothing at all")
  void offIsNothing() {
    when(settings.getBoolean(ApplicationSettingKey.TRACKING_ENABLED)).thenReturn(false);
    assertThat(service(false).current()).isEmpty();
  }

  @Test
  @DisplayName("'none' is a provider only in the dropdown")
  void noneIsNothing() {
    when(settings.getString(ApplicationSettingKey.TRACKING_PROVIDER)).thenReturn("none");
    assertThat(service(false).current()).isEmpty();
  }

  @Test
  @DisplayName("no site id means nothing to measure into")
  void missingSiteIdIsNothing() {
    when(settings.getString(ApplicationSettingKey.TRACKING_SITE_ID)).thenReturn("   ");
    assertThat(service(false).current()).isEmpty();
  }

  @Test
  @DisplayName("a self-hosted backend without a host is not a configuration")
  void selfHostedNeedsAHost() {
    when(settings.getString(ApplicationSettingKey.TRACKING_PROVIDER)).thenReturn("matomo");
    when(settings.getString(ApplicationSettingKey.TRACKING_SITE_ID)).thenReturn("1");
    when(settings.getString(ApplicationSettingKey.TRACKING_HOST)).thenReturn("");
    assertThat(service(false).current()).isEmpty();
  }

  @Test
  @DisplayName("a private host is refused unless the deployment allows it")
  void privateHostNeedsTheDeploymentProperty() {
    when(settings.getString(ApplicationSettingKey.TRACKING_PROVIDER)).thenReturn("matomo");
    when(settings.getString(ApplicationSettingKey.TRACKING_SITE_ID)).thenReturn("1");
    when(settings.getString(ApplicationSettingKey.TRACKING_HOST))
        .thenReturn("http://10.0.0.5:8080/");

    // The whole point of the property: an admin with the settings page cannot
    // point this server at an internal address on their own.
    assertThat(service(false).current()).isEmpty();
    assertThat(service(true).current())
        .hasValueSatisfying(
            config -> {
              assertThat(config.collectBaseUrl()).isEqualTo("http://10.0.0.5:8080");
              assertThat(config.scriptUrl()).isEqualTo("http://10.0.0.5:8080/matomo.js");
            });
  }

  @Test
  @DisplayName("the cloud metadata address stays blocked either way")
  void blocksMetadataAddress() {
    when(settings.getString(ApplicationSettingKey.TRACKING_PROVIDER)).thenReturn("matomo");
    when(settings.getString(ApplicationSettingKey.TRACKING_SITE_ID)).thenReturn("1");
    when(settings.getString(ApplicationSettingKey.TRACKING_HOST))
        .thenReturn("http://169.254.169.254/");

    assertThat(service(false).current()).isEmpty();
  }

  @Test
  @DisplayName("'none' for the IP means none is forwarded")
  void forwardIpIsOptional() {
    when(settings.getString(ApplicationSettingKey.TRACKING_FORWARD_CLIENT_IP)).thenReturn("none");
    assertThat(service(false).current())
        .hasValueSatisfying(
            c -> assertThat(c.forwardClientIp()).isEqualTo(ClientIpForwarding.NONE));
  }

  @Test
  @DisplayName("choosing full addresses loosens nothing else (#712)")
  void fullAddressesLeaveTheOtherGatesAlone() {
    when(settings.getString(ApplicationSettingKey.TRACKING_FORWARD_CLIENT_IP)).thenReturn("full");

    // The promise attached to the opt-in: consent and Do-Not-Track keep applying
    // exactly as configured. They are separate settings, and this pins that they
    // stay separate rather than being read together by some later convenience.
    assertThat(service(false).current())
        .hasValueSatisfying(
            config -> {
              assertThat(config.forwardClientIp()).isEqualTo(ClientIpForwarding.FULL);
              assertThat(config.consentRequired()).isTrue();
              assertThat(config.respectDnt()).isTrue();
            });
  }
}
