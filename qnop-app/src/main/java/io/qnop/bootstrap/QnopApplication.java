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

import io.qnop.security.QnopProperties;
import io.qnop.web.security.ratelimit.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the qnop Community server.
 *
 * <p>The layered modules use sibling packages rather than sub-packages of this bootstrap, so
 * component scanning is pointed at the {@code io.qnop} root (see ADR-0004 for the layout and
 * ADR-0020 for the runtime wiring). JPA entities and Spring Data repositories live in their own
 * sibling packages, so they are registered explicitly (the data model arrived with issue #11).
 */
@SpringBootApplication(scanBasePackages = "io.qnop")
@EnableConfigurationProperties({QnopProperties.class, RateLimitProperties.class})
@EntityScan("io.qnop.entity")
@EnableJpaRepositories("io.qnop.repository")
@EnableScheduling
public class QnopApplication {

  public static void main(String[] args) {
    applyDefaultHttpUrlConnectionTimeouts();
    SpringApplication.run(QnopApplication.class, args);
  }

  /**
   * Bounds the JDK {@code HttpURLConnection} defaults (issue #342) as a safety net for outbound
   * calls made over the legacy client that carries no timeout of its own — notably Spring
   * Security's OIDC issuer discovery ({@code ClientRegistrations}) and JWK-set fetching, which
   * build a default {@code RestTemplate}. Set only when the operator has not already supplied a
   * value via {@code -Dsun.net.client.default*Timeout}, so an explicit override always wins.
   * Clients we construct ourselves (S3, SMTP, the GitHub {@code RestClient}) carry their own
   * timeouts and are unaffected.
   */
  static void applyDefaultHttpUrlConnectionTimeouts() {
    setPropertyIfAbsent("sun.net.client.defaultConnectTimeout", "5000");
    setPropertyIfAbsent("sun.net.client.defaultReadTimeout", "15000");
  }

  private static void setPropertyIfAbsent(String key, String value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, value);
    }
  }
}
