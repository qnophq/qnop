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
package io.qnop.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The JDK HttpURLConnection timeout backstop for OIDC discovery / JWK fetching (issue #342). */
class QnopApplicationTest {

  private static final String CONNECT = "sun.net.client.defaultConnectTimeout";
  private static final String READ = "sun.net.client.defaultReadTimeout";

  private final Map<String, Optional<String>> saved =
      Map.of(
          CONNECT, Optional.ofNullable(System.getProperty(CONNECT)),
          READ, Optional.ofNullable(System.getProperty(READ)));

  @BeforeEach
  void clear() {
    System.clearProperty(CONNECT);
    System.clearProperty(READ);
  }

  @AfterEach
  void restore() {
    saved.forEach(
        (key, value) ->
            value.ifPresentOrElse(
                v -> System.setProperty(key, v), () -> System.clearProperty(key)));
  }

  @Test
  @DisplayName("sets finite HttpURLConnection connect/read timeouts when unset")
  void setsDefaultsWhenUnset() {
    QnopApplication.applyDefaultHttpUrlConnectionTimeouts();

    assertThat(System.getProperty(CONNECT)).isEqualTo("5000");
    assertThat(System.getProperty(READ)).isEqualTo("15000");
  }

  @Test
  @DisplayName("never overrides an operator-supplied -D value")
  void respectsOperatorOverride() {
    System.setProperty(CONNECT, "1234");
    System.setProperty(READ, "9999");

    QnopApplication.applyDefaultHttpUrlConnectionTimeouts();

    assertThat(System.getProperty(CONNECT)).isEqualTo("1234");
    assertThat(System.getProperty(READ)).isEqualTo("9999");
  }
}
