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
package io.qnop.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.security.QnopProperties;
import io.qnop.security.SecretWriteFormat;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The documented secrets-at-rest environment variables (issue #927) reach {@link QnopProperties}
 * through the shipped {@code application.yml}, exactly as an operator sets them.
 */
class SecretsPropertiesBindingTest {

  private static QnopProperties.Auth bind(Map<String, Object> env) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    sources.addFirst(new SystemEnvironmentPropertySource("env", env));
    for (PropertySource<?> yaml :
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))) {
      sources.addLast(yaml);
    }
    return Binder.get(environment).bind("qnop", QnopProperties.class).get().auth();
  }

  @Test
  void bindsTheDocumentedRotationAndWriteFormatVariables() throws IOException {
    QnopProperties.Auth auth =
        bind(
            Map.of(
                "QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS", "previous-key-from-the-environment-0123",
                "QNOP_AUTH_ENCRYPTION_SALT_PREVIOUS", "fedcba9876543210",
                "QNOP_AUTH_SECRETS_WRITE_FORMAT", "legacy"));

    assertThat(auth.encryptionKeyPrevious()).isEqualTo("previous-key-from-the-environment-0123");
    assertThat(auth.encryptionSaltPrevious()).isEqualTo("fedcba9876543210");
    assertThat(auth.secrets().writeFormat()).isEqualTo(SecretWriteFormat.LEGACY);
  }

  @Test
  void defaultsToNoRotationAndTheV2Format() throws IOException {
    QnopProperties.Auth auth = bind(Map.of());

    assertThat(auth.hasEncryptionKeyPrevious()).isFalse();
    assertThat(auth.encryptionSaltPrevious()).isEmpty();
    assertThat(auth.secrets().writeFormat()).isEqualTo(SecretWriteFormat.V2);
  }
}
