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

import io.qnop.service.http.HttpClientProperties;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the configurable outbound timeouts (issue #342) to the JDK {@code HttpURLConnection}
 * defaults, as a bound for outbound calls made over the legacy client that carries no timeout of
 * its own — notably Spring Security's OIDC issuer discovery ({@code ClientRegistrations}) and
 * JWK-set fetching, which build a default {@code RestTemplate}. Runs at context initialization,
 * well before any (user-triggered) OIDC discovery or login. An operator-supplied {@code
 * -Dsun.net.client.default*Timeout} always wins. Clients qnop constructs itself (S3, SMTP, the
 * GitHub {@code RestClient}) carry their own timeouts and are unaffected.
 */
@Configuration(proxyBeanMethods = false)
class HttpUrlConnectionTimeoutConfiguration {

  HttpUrlConnectionTimeoutConfiguration(HttpClientProperties properties) {
    apply(properties.outboundConnectTimeout(), properties.outboundReadTimeout());
  }

  static void apply(Duration connectTimeout, Duration readTimeout) {
    setIfAbsent("sun.net.client.defaultConnectTimeout", connectTimeout);
    setIfAbsent("sun.net.client.defaultReadTimeout", readTimeout);
  }

  private static void setIfAbsent(String key, Duration value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, Long.toString(value.toMillis()));
    }
  }
}
