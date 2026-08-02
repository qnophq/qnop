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
package io.qnop.limits;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.limits.InstanceLimitProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The quotas have to arrive the way a deployment actually sets them: as environment variables
 * (issue #687). A property-only test would pass on a binding that silently drops them.
 */
class InstanceLimitPropertiesBindingTest {

  @Test
  @DisplayName("QNOP_LIMITS_MAX_USERS reaches maxUsers")
  void bindsFromEnvironmentVariables() {
    new ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(Config.class)
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "test-env",
                            Map.of(
                                "QNOP_LIMITS_MAX_USERS", "30",
                                "QNOP_LIMITS_MAX_TEAMS", "5"))))
        .run(
            context -> {
              InstanceLimitProperties limits = context.getBean(InstanceLimitProperties.class);
              assertThat(limits.maxUsers()).isEqualTo(30);
              assertThat(limits.maxTeams()).isEqualTo(5);
              assertThat(limits.maxTeamMembers()).isZero();
            });
  }

  @EnableConfigurationProperties(InstanceLimitProperties.class)
  static class Config {}
}
