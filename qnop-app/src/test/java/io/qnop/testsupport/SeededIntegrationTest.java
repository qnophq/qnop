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
package io.qnop.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.service.JwtTokenService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base for integration tests that run against the shared dummy dataset (issue #163). Each test
 * cleans and reloads {@code testdata/db/seed.sql} in {@link BeforeEach} and wipes it again in
 * {@link AfterEach}, so every seeded test sees exactly the dummy dataset and the JVM-shared
 * Testcontainers database is left empty for non-seeded IT classes.
 *
 * <p>The seed is loaded through {@link DatabasePopulatorUtils} on an autocommit pool connection
 * rather than relying on {@code @Transactional} rollback: the Boot-default {@code
 * JpaTransactionManager} has no {@code DataSource} set, so a test transaction never binds a
 * connection to the {@code DataSource} — a script run via {@code DataSourceUtils} would therefore
 * commit on a separate connection and never roll back. Explicit clean+seed is deterministic
 * regardless of the transaction manager's wiring.
 *
 * <p>Authentication is fast: {@link #token(UUID)} mints a real access token for a seeded user via
 * {@link JwtTokenService} (correct role claim, no login round-trip or rate limit), while {@link
 * #login(String, String)} drives the real {@code POST /auth/login} for the flows that must exercise
 * it. The seeded ids and the shared password are exposed as constants.
 *
 * <p>Auth rate limits (ADR-0027) are IP-keyed and the buckets live for the JVM, so a suite that
 * logs in repeatedly would otherwise trip the 10/60s login limit. The limits are raised here to a
 * very high ceiling so flow tests never throttle each other; a dedicated rate-limit test sets its
 * own low limits in its own context.
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "qnop.auth.rate-limit.login.max-attempts=1000000",
      "qnop.auth.rate-limit.refresh.max-attempts=1000000",
      "qnop.auth.rate-limit.change-password.max-attempts=1000000",
      "qnop.auth.rate-limit.register.max-attempts=1000000",
      "qnop.auth.rate-limit.forgot-password.max-attempts=1000000"
    })
public abstract class SeededIntegrationTest extends AbstractIntegrationTest {

  /** The plaintext behind every seeded user's bcrypt hash (for the real-login flows). */
  public static final String SEED_PASSWORD = "Test-Pass-1234!";

  /**
   * Name of the HttpOnly refresh-token cookie issued by {@code /auth/login} and {@code /refresh}.
   */
  public static final String REFRESH_COOKIE = "qnop_refresh";

  public static final UUID ADMIN_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");
  public static final UUID ADMIN2_ID = UUID.fromString("a0000000-0000-0000-0000-000000000008");
  public static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000002");
  public static final UUID MEMBER2_ID = UUID.fromString("a0000000-0000-0000-0000-000000000003");
  public static final UUID AUDITOR_ID = UUID.fromString("a0000000-0000-0000-0000-000000000004");
  public static final UUID DISABLED_ID = UUID.fromString("a0000000-0000-0000-0000-000000000005");
  public static final UUID PWCHANGE_ID = UUID.fromString("a0000000-0000-0000-0000-000000000006");
  public static final UUID EXTERNAL_ID = UUID.fromString("a0000000-0000-0000-0000-000000000007");

  public static final UUID TEAM_ALPHA_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001");
  public static final UUID TEAM_BETA_ID = UUID.fromString("b0000000-0000-0000-0000-000000000002");

  public static final UUID OIDC_ENABLED_ID =
      UUID.fromString("d0000000-0000-0000-0000-000000000001");
  public static final UUID OIDC_DISABLED_ID =
      UUID.fromString("d0000000-0000-0000-0000-000000000002");

  @Autowired protected MockMvc mockMvc;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void loadSeed() {
    runScript("db/clean.sql");
    runScript("db/seed.sql");
  }

  @AfterEach
  void wipeSeed() {
    runScript("db/clean.sql");
  }

  /** Runs a SQL script from {@code testdata/} on a managed (autocommit) pool connection. */
  private void runScript(String relativePath) {
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(new FileSystemResource(TestData.path(relativePath))),
        dataSource);
  }

  /** A bearer access token for a seeded user (carries the user's real role claim). */
  protected String token(UUID userId) {
    return jwtTokenService.issueAccessToken(userId);
  }

  /**
   * Drives the real {@code POST /api/v1/auth/login}. The returned result carries the access token
   * in its JSON body and the rotating refresh token in the {@value #REFRESH_COOKIE} cookie.
   */
  protected MvcResult login(String usernameOrEmail, String password) throws Exception {
    String body =
        "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(usernameOrEmail, password);
    return mockMvc
        .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  /** The refresh cookie set on a response (e.g. from {@link #login}), or {@code null} if absent. */
  protected Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie(REFRESH_COOKIE);
  }
}
