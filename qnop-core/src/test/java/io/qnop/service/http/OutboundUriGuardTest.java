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
package io.qnop.service.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Table-driven checks of the lexical half of the outbound guard (issue #904). */
class OutboundUriGuardTest {

  @ParameterizedTest(name = "{0} is refused")
  @ValueSource(
      strings = {
        "http://127.0.0.1/",
        "http://10.1.2.3/",
        "http://100.64.0.1/",
        "http://100.100.100.200/",
        "http://0.0.0.0/",
        "http://224.0.0.1/",
        "http://255.255.255.255/",
        "http://169.254.169.254/latest",
        "http://168.63.129.16/",
        "http://[::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://[::ffff:7f00:1]/",
        "http://[::127.0.0.1]/",
        "http://[64:ff9b::7f00:1]/",
        "http://[fd00:ec2::254]/",
        "http://[fe80::1]/",
        "http://[ff02::1]/",
        "http://localhost/",
        "http://LOCALHOST./",
        "http://idp.localhost/",
        "http://metadata.google.internal/",
        "http://printer.local/"
      })
  void refusesBlockedDestinations(String uri) {
    assertThatThrownBy(() -> OutboundUriGuard.requireAllowedHttpUri(uri, "url", true, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} is refused even when private hosts are allowed")
  @ValueSource(
      strings = {
        "http://2130706433/",
        "http://0x7f000001/",
        "http://0x7f.1/",
        "http://0x7f.0.0.1/",
        "http://0177.0.0.1/",
        "http://127.1/",
        "http://127.0.1/",
        "http://127.000.000.001/",
        "http://010.0.0.1/",
        "http://256.0.0.1/",
        "http://1.2.3.4.5/",
        "http://example.com.0x1/"
      })
  void refusesNonCanonicalNumericHosts(String uri) {
    assertThatThrownBy(() -> OutboundUriGuard.requireAllowedHttpUri(uri, "url", true, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OutboundUriGuard.requireAllowedHttpUri(uri, "url", true, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} is accepted")
  @ValueSource(
      strings = {
        "https://accounts.google.com",
        "https://login.microsoftonline.com/common/v2.0",
        "https://8.8.8.8/auth",
        "https://[2001:4860:4860::8888]/",
        "https://analytics.example.com:8443/matomo",
        "https://1password.example/",
        "https://id.example.co/realms/1"
      })
  void acceptsPublicDestinations(String uri) {
    assertThatCode(() -> OutboundUriGuard.requireAllowedHttpUri(uri, "url", true, false))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0} is accepted with the private-host opt-in")
  @ValueSource(
      strings = {
        "http://localhost:8081/realms/qnop",
        "http://127.0.0.1:8080/",
        "http://keycloak.internal/realms/qnop",
        "http://[::1]:8080/"
      })
  void optInAllowsPrivateDestinations(String uri) {
    assertThatCode(() -> OutboundUriGuard.requireAllowedHttpUri(uri, "url", true, true))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0} is a non-canonical numeric host: {1}")
  @org.junit.jupiter.params.provider.CsvSource({
    "127.0.0.1, false",
    "2130706433, true",
    "0x7f.1, true",
    "example.com, false",
    "1password.example, false",
    "a.b.c.07, true",
    "0.0.0.0, false"
  })
  void numericHostClassification(String host, boolean nonCanonical) {
    assertThat(OutboundUriGuard.isNonCanonicalNumericHost(host)).isEqualTo(nonCanonical);
  }
}
