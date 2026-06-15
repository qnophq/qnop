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
package io.qnop.service.oidc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OidcSsrfPolicyTest {

  private final OidcSsrfPolicy policy = new OidcSsrfPolicy(false);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/auth",
        "http://localhost/auth",
        "https://idp.localhost/auth",
        "http://10.0.0.5/auth",
        "http://192.168.1.10/auth",
        "http://172.16.5.5/auth",
        "http://169.254.169.254/latest/meta-data",
        "http://[::1]/auth",
        "http://0.0.0.0/auth"
      })
  @DisplayName("blocks private/loopback/link-local/metadata destinations")
  void blocksPrivateDestinations(String uri) {
    assertThatThrownBy(() -> policy.requirePublicHttpUri(uri, "issuerUri", true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://accounts.google.com",
        "https://login.microsoftonline.com/common/v2.0",
        "https://8.8.8.8/auth"
      })
  @DisplayName("allows public http(s) destinations")
  void allowsPublic(String uri) {
    assertThatCode(() -> policy.requirePublicHttpUri(uri, "issuerUri", true))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rejects non-http(s) schemes")
  void rejectsNonHttpScheme() {
    assertThatThrownBy(() -> policy.requirePublicHttpUri("ftp://example.com", "issuerUri", true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a required blank value is rejected; an optional blank value is accepted")
  void blankHandling() {
    assertThatThrownBy(() -> policy.requirePublicHttpUri("  ", "issuerUri", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> policy.requirePublicHttpUri(null, "issuerUri", false))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the opt-in escape hatch allows private hosts but still enforces the scheme")
  void escapeHatch() {
    OidcSsrfPolicy relaxed = new OidcSsrfPolicy(true);
    assertThatCode(() -> relaxed.requirePublicHttpUri("http://127.0.0.1/auth", "issuerUri", true))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> relaxed.requirePublicHttpUri("ftp://127.0.0.1", "issuerUri", true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
