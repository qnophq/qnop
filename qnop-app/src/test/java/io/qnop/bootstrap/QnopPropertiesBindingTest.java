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

import io.qnop.security.QnopProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies the fail-fast contract (ADR-0021): a context bound to placeholder secrets refuses to
 * start, while strong secrets bind cleanly. Runs without a database — only the properties binding
 * and validation are exercised.
 */
class QnopPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Configuration
  @EnableConfigurationProperties(QnopProperties.class)
  static class TestConfig {}

  @Test
  void insecureDefaultSecretFailsFast() {
    runner
        .withPropertyValues(
            "qnop.auth.jwt-secret=CHANGE_ME",
            "qnop.auth.encryption-key=CHANGE_ME",
            "qnop.auth.encryption-salt=CHANGE_ME",
            "qnop.cors.allowed-origins=http://localhost:5173")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void strongSecretsBindSuccessfully() {
    runner
        .withPropertyValues(
            "qnop.auth.jwt-secret=binding-test-jwt-secret-0123456789",
            "qnop.auth.encryption-key=binding-test-encryption-key-0123456789",
            "qnop.auth.encryption-salt=0123456789abcdef0123456789abcdef",
            "qnop.cors.allowed-origins=http://localhost:5173,https://app.example.com")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              QnopProperties properties = context.getBean(QnopProperties.class);
              assertThat(properties.auth().jwtSecret())
                  .isEqualTo("binding-test-jwt-secret-0123456789");
              assertThat(properties.cors().allowedOrigins())
                  .containsExactly("http://localhost:5173", "https://app.example.com");
            });
  }
}
