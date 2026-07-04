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
import io.qnop.service.http.HttpClientProperties;
import io.qnop.service.review.ReanchoringProperties;
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
@EnableConfigurationProperties({
  QnopProperties.class,
  RateLimitProperties.class,
  HttpClientProperties.class,
  ReanchoringProperties.class
})
@EntityScan("io.qnop.entity")
@EnableJpaRepositories("io.qnop.repository")
@EnableScheduling
public class QnopApplication {

  public static void main(String[] args) {
    SpringApplication.run(QnopApplication.class, args);
  }
}
