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

import io.qnop.service.storage.S3Properties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with the database / object-storage credentials that {@code application.yml} and
 * the local-dev compose stack ship as fallbacks (issue #922) — the same fail-fast stance
 * {@code @ValidSecret} takes for the auth secrets (ADR-0022). Those values are public, so a
 * deployment that runs on them is open to anyone who can reach the database or the bucket.
 *
 * <p>Only the {@code dev} profile tolerates them; {@code ./gradlew :qnop-app:bootRun} activates it
 * for local development. The check reads the <em>effective</em> connection details rather than the
 * raw properties, so a connection supplied another way (e.g. a Testcontainers service connection)
 * is judged by what is actually used.
 */
@Component
class DefaultCredentialsGuard implements InitializingBean {

  /** The profile under which the shipped local-development credentials are accepted. */
  static final String DEV_PROFILE = "dev";

  /** {@code application.yml}'s fallback for {@code QNOP_DB_PASSWORD}. */
  static final String DEFAULT_DB_PASSWORD = "qnop";

  /** {@code application.yml}'s fallback for {@code QNOP_S3_SECRET_KEY}. */
  static final String DEFAULT_S3_SECRET_KEY = "qnop_secret";

  private static final Logger log = LoggerFactory.getLogger(DefaultCredentialsGuard.class);

  private final Environment environment;
  private final ObjectProvider<JdbcConnectionDetails> jdbcConnectionDetails;
  private final S3Properties s3Properties;

  DefaultCredentialsGuard(
      Environment environment,
      ObjectProvider<JdbcConnectionDetails> jdbcConnectionDetails,
      S3Properties s3Properties) {
    this.environment = environment;
    this.jdbcConnectionDetails = jdbcConnectionDetails;
    this.s3Properties = s3Properties;
  }

  @Override
  public void afterPropertiesSet() {
    JdbcConnectionDetails jdbc = jdbcConnectionDetails.getIfAvailable();
    verify(environment, jdbc == null ? null : jdbc.getPassword(), s3Properties.secretKey());
  }

  /**
   * Fails when a shipped default credential is in use and the {@code dev} profile is not active.
   *
   * @param environment the environment whose active profiles decide whether defaults are tolerated
   * @param dbPassword the effective database password (may be {@code null})
   * @param s3SecretKey the effective object-storage secret key (may be {@code null})
   * @throws IllegalStateException naming every offending setting
   */
  static void verify(Environment environment, String dbPassword, String s3SecretKey) {
    List<String> defaults = new ArrayList<>();
    if (DEFAULT_DB_PASSWORD.equals(dbPassword)) {
      defaults.add("QNOP_DB_PASSWORD (spring.datasource.password)");
    }
    if (DEFAULT_S3_SECRET_KEY.equals(s3SecretKey)) {
      defaults.add("QNOP_S3_SECRET_KEY (qnop.s3.secret-key)");
    }
    if (defaults.isEmpty()) {
      return;
    }
    if (environment.matchesProfiles(DEV_PROFILE)) {
      log.warn(
          "Running with the shipped local-development credentials for {} — acceptable only for"
              + " local development.",
          String.join(", ", defaults));
      return;
    }
    throw new IllegalStateException(
        "Refusing to start: "
            + String.join(", ", defaults)
            + " still use the shipped local-development default. Set a real, unique value, or"
            + " activate the '"
            + DEV_PROFILE
            + "' profile (SPRING_PROFILES_ACTIVE="
            + DEV_PROFILE
            + ") for local development only.");
  }
}
