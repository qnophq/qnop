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

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs one WARN line at startup when the guarded outbound clients run in proxy mode (issue #940),
 * so the weaker trust model is visible in the logs rather than only in configuration. Logs the
 * proxy's host and port and the number of non-proxy-host entries — proxy authentication is not
 * supported, so there are no credentials to keep out of the line.
 */
@Component
public class OutboundProxyStartupNotice {

  private static final Logger log = LoggerFactory.getLogger(OutboundProxyStartupNotice.class);

  public OutboundProxyStartupNotice(HttpClientProperties properties) {
    message(properties.proxy()).ifPresent(log::warn);
  }

  /** The startup line for {@code proxy}, or empty when proxy mode is off. */
  static Optional<String> message(HttpClientProperties.Proxy proxy) {
    if (!proxy.enabled()) {
      return Optional.empty();
    }
    return Optional.of(
        "Outbound proxy mode active: OIDC and usage-tracking requests are routed via "
            + proxy.host()
            + ":"
            + proxy.port()
            + " ("
            + proxy.nonProxyHosts().size()
            + " non-proxy host entries stay direct). Proxied destinations are checked lexically"
            + " only; the proxy's egress ACL is the remaining control (ADR-0056, issue #940).");
  }
}
