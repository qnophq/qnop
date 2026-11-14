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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How an unattended Prometheus may read {@code /actuator/prometheus} (issue #962), bound from
 * {@code qnop.metrics.*}.
 *
 * <p>An admin access token is short-lived and rotates, so it cannot drive a scrape job. This token
 * is the long-lived alternative: a static bearer credential that opens the scrape endpoint and
 * nothing else. It is off unless set. A value that is shorter than {@value #MIN_TOKEN_LENGTH}
 * characters or contains whitespace refuses to start: the first is guessable, the second (a
 * trailing newline from a secrets file, say) could never match what a scraper sends.
 *
 * <p>The checks live in the constructor rather than in bean-validation annotations on purpose.
 * Boot's failure report for a rejected constraint prints the rejected value, which here would be
 * most of a credential; these messages name the rule and nothing else.
 *
 * @param scrapeToken the bearer token a scraper presents; blank means the feature is off
 */
@ConfigurationProperties(prefix = "qnop.metrics")
public record MetricsScrapeProperties(String scrapeToken) {

  static final int MIN_TOKEN_LENGTH = 32;

  public MetricsScrapeProperties {
    // Blank and absent mean the same thing: an unset environment variable binds as "".
    scrapeToken = scrapeToken == null || scrapeToken.isBlank() ? null : scrapeToken;
    if (scrapeToken != null) {
      if (scrapeToken.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException(
            "qnop.metrics.scrape-token must not contain whitespace (check for a trailing newline)");
      }
      if (scrapeToken.length() < MIN_TOKEN_LENGTH) {
        throw new IllegalArgumentException(
            "qnop.metrics.scrape-token must be at least "
                + MIN_TOKEN_LENGTH
                + " characters when set");
      }
    }
  }
}
