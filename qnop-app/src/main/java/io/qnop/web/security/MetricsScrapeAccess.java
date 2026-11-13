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
package io.qnop.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Decides whether a request may read the Prometheus scrape without an admin login (issue #962).
 *
 * <p>Two opt-in ways in, both off by default:
 *
 * <ul>
 *   <li><b>The management port.</b> When {@code management.server.port} moves Actuator to its own
 *       port, a scrape arriving there is let through: the port is not published through the
 *       ingress, so reaching it is the credential.
 *   <li><b>The scrape token.</b> A request carrying {@code Authorization: Bearer <token>} equal to
 *       {@code qnop.metrics.scrape-token}.
 * </ul>
 *
 * <p>It matches {@code GET /actuator/prometheus} exactly and nothing else. Anything it does not
 * match falls through to the ordinary rules, where the scrape needs {@code ROLE_ADMIN}, so a
 * mistake here fails closed.
 */
@Component
public class MetricsScrapeAccess implements RequestMatcher {

  static final String SCRAPE_PATH = "/actuator/prometheus";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String MANAGEMENT_PORT_PROPERTY = "management.server.port";
  private static final String SERVER_PORT_PROPERTY = "server.port";
  private static final String LOCAL_MANAGEMENT_PORT_PROPERTY = "local.management.port";
  private static final int DEFAULT_SERVER_PORT = 8080;

  private final byte[] tokenDigest;
  private final Environment environment;

  public MetricsScrapeAccess(MetricsScrapeProperties properties, Environment environment) {
    this.tokenDigest = properties.scrapeToken() == null ? null : sha256(properties.scrapeToken());
    this.environment = environment;
  }

  @Override
  public boolean matches(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod()) || !SCRAPE_PATH.equals(pathOf(request))) {
      return false;
    }
    return arrivedOnManagementPort(request) || carriesScrapeToken(request);
  }

  private static String pathOf(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  /**
   * True when Actuator has a port of its own and this request came in on it. The socket's local
   * port is used, never the {@code Host} header or a forwarded port, because those are the caller's
   * to choose.
   */
  private boolean arrivedOnManagementPort(HttpServletRequest request) {
    Integer configured = environment.getProperty(MANAGEMENT_PORT_PROPERTY, Integer.class);
    if (configured == null || configured < 0) {
      return false;
    }
    int serverPort =
        environment.getProperty(SERVER_PORT_PROPERTY, Integer.class, DEFAULT_SERVER_PORT);
    if (configured != 0 && configured == serverPort) {
      return false; // same port as the application: no separation, no exemption
    }
    // "0" asks for a random port; Boot publishes the one it got once the server is up.
    Integer actual = environment.getProperty(LOCAL_MANAGEMENT_PORT_PROPERTY, Integer.class);
    int managementPort = actual != null ? actual : configured;
    return managementPort > 0 && request.getLocalPort() == managementPort;
  }

  private boolean carriesScrapeToken(HttpServletRequest request) {
    if (tokenDigest == null) {
      return false;
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    // The scheme is case-insensitive (RFC 9110), and the resource server reads it that way too.
    if (header == null
        || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return false;
    }
    return isScrapeToken(header.substring(BEARER_PREFIX.length()));
  }

  /**
   * Whether {@code credential} is the configured scrape token; false when none is configured.
   *
   * <p>Digests are compared, in constant time: neither the length nor a matching prefix of the
   * configured token can be learned from how long the comparison takes.
   */
  public boolean isScrapeToken(String credential) {
    return tokenDigest != null
        && credential != null
        && MessageDigest.isEqual(tokenDigest, sha256(credential));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }
}
