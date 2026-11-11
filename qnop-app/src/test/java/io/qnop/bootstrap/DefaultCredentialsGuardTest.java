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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.service.storage.S3Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * The shipped local-development DB/S3 credentials are refused outside the {@code dev} profile
 * (issue #922), mirroring the fail-fast that {@code @ValidSecret} gives the auth secrets.
 */
class DefaultCredentialsGuardTest {

  private static MockEnvironment withProfiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  void defaultDatabasePasswordFailsOutsideDev() {
    assertThatThrownBy(
            () -> DefaultCredentialsGuard.verify(withProfiles(), "qnop", "a-real-s3-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("QNOP_DB_PASSWORD")
        .hasMessageNotContaining("QNOP_S3_SECRET_KEY");
  }

  @Test
  void defaultObjectStorageSecretFailsOutsideDev() {
    assertThatThrownBy(
            () ->
                DefaultCredentialsGuard.verify(withProfiles("prod"), "a-real-db-pw", "qnop_secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("QNOP_S3_SECRET_KEY")
        .hasMessageNotContaining("QNOP_DB_PASSWORD");
  }

  @Test
  void bothDefaultsAreReportedTogether() {
    assertThatThrownBy(() -> DefaultCredentialsGuard.verify(withProfiles(), "qnop", "qnop_secret"))
        .hasMessageContaining("QNOP_DB_PASSWORD")
        .hasMessageContaining("QNOP_S3_SECRET_KEY")
        .hasMessageContaining("dev");
  }

  @Test
  void devProfileToleratesTheLocalDefaults() {
    assertThatCode(() -> DefaultCredentialsGuard.verify(withProfiles("dev"), "qnop", "qnop_secret"))
        .doesNotThrowAnyException();
  }

  @Test
  void realCredentialsPassWithoutAProfile() {
    assertThatCode(() -> DefaultCredentialsGuard.verify(withProfiles(), "s3cr3t-db", "s3cr3t-s3"))
        .doesNotThrowAnyException();
  }

  @Test
  void defaultLiquibasePasswordFailsOutsideDev() {
    MockEnvironment environment = withProfiles();
    environment.setProperty("spring.liquibase.password", "qnop");
    assertThatThrownBy(() -> DefaultCredentialsGuard.verify(environment, "s3cr3t-db", "s3cr3t-s3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.liquibase.password");
  }

  @Test
  void missingDatabasePasswordIsNotTreatedAsTheDefault() {
    assertThatCode(() -> DefaultCredentialsGuard.verify(withProfiles(), null, "s3cr3t-s3"))
        .doesNotThrowAnyException();
  }

  // --- wiring: the guard judges the effective connection details, not the raw properties ---

  private static ApplicationContextRunner contextWithDbPassword(String dbPassword) {
    return new ApplicationContextRunner()
        .withUserConfiguration(GuardConfig.class)
        .withBean(JdbcConnectionDetails.class, () -> jdbcDetails(dbPassword))
        .withPropertyValues("qnop.s3.bucket=b", "qnop.s3.access-key=k");
  }

  private static JdbcConnectionDetails jdbcDetails(String password) {
    return new JdbcConnectionDetails() {
      @Override
      public String getUsername() {
        return "qnop";
      }

      @Override
      public String getPassword() {
        return password;
      }

      @Override
      public String getJdbcUrl() {
        return "jdbc:postgresql://localhost:5432/qnop";
      }
    };
  }

  @Configuration
  @EnableConfigurationProperties(S3Properties.class)
  static class GuardConfig {
    @Bean
    DefaultCredentialsGuard defaultCredentialsGuard(
        Environment environment, ObjectProvider<JdbcConnectionDetails> jdbc, S3Properties s3) {
      return new DefaultCredentialsGuard(environment, jdbc, s3);
    }
  }

  @Test
  void contextRefusesToStartOnTheDefaultCredentials() {
    contextWithDbPassword("qnop")
        .withPropertyValues("qnop.s3.secret-key=qnop_secret")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void contextStartsWhenTheEffectiveCredentialsAreReal() {
    contextWithDbPassword("container-supplied-password")
        .withPropertyValues("spring.datasource.password=qnop", "qnop.s3.secret-key=a-real-secret")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void contextStartsOnTheDefaultsUnderTheDevProfile() {
    contextWithDbPassword("qnop")
        .withPropertyValues("qnop.s3.secret-key=qnop_secret", "spring.profiles.active=dev")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
