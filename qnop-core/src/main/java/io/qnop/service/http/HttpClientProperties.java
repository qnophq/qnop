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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable timeouts for the outbound HTTP paths (issue #342), so a slow or hung dependency can
 * never pin a thread indefinitely. Every value is overridable via {@code qnop.http-client.*} (or
 * the matching {@code QNOP_HTTP_CLIENT_*} environment variable); the defaults below apply only when
 * a value is not supplied.
 *
 * <ul>
 *   <li>{@code outbound*} — the connect/read timeouts for HTTP clients qnop makes calls through:
 *       the GitHub email fetch (OIDC login) and, applied as JDK {@code HttpURLConnection} defaults,
 *       the Spring Security OIDC issuer-discovery and JWK-set fetches.
 *   <li>{@code smtp*} — the SMTP transport connect/read/write timeouts for outgoing mail.
 * </ul>
 *
 * @param outboundConnectTimeout TCP-connect ceiling for outbound HTTP (default 5s)
 * @param outboundReadTimeout response-read ceiling for outbound HTTP (default 15s)
 * @param smtpConnectTimeout SMTP connect ceiling (default 10s)
 * @param smtpReadTimeout SMTP read ceiling (default 30s)
 * @param smtpWriteTimeout SMTP write ceiling (default 30s)
 */
@ConfigurationProperties(prefix = "qnop.http-client")
public record HttpClientProperties(
    Duration outboundConnectTimeout,
    Duration outboundReadTimeout,
    Duration smtpConnectTimeout,
    Duration smtpReadTimeout,
    Duration smtpWriteTimeout) {

  public HttpClientProperties {
    outboundConnectTimeout = orDefault(outboundConnectTimeout, Duration.ofSeconds(5));
    outboundReadTimeout = orDefault(outboundReadTimeout, Duration.ofSeconds(15));
    smtpConnectTimeout = orDefault(smtpConnectTimeout, Duration.ofSeconds(10));
    smtpReadTimeout = orDefault(smtpReadTimeout, Duration.ofSeconds(30));
    smtpWriteTimeout = orDefault(smtpWriteTimeout, Duration.ofSeconds(30));
  }

  private static Duration orDefault(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }
}
