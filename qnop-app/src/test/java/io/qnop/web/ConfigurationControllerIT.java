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
package io.qnop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.UserRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end guard for the effective-configuration endpoint (issue #522). Proves — through the real
 * security chain and the real bound {@code @ConfigurationProperties} beans — that an ADMIN sees the
 * grouped, redacted tree and that <em>no</em> secret value ever reaches the wire. The secret values
 * asserted absent are exactly the ones {@link AbstractIntegrationTest} binds into the test context,
 * so a regression that leaked a bound secret would fail here.
 */
@AutoConfigureMockMvc
@Transactional
class ConfigurationControllerIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery";
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  // The exact secret material AbstractIntegrationTest binds — none of it may appear in the
  // response.
  private static final String JWT_SECRET = "integration-test-jwt-secret-0123456789";
  private static final String ENCRYPTION_KEY = "integration-test-encryption-key-0123456789";
  private static final String ENCRYPTION_SALT = "0123456789abcdef0123456789abcdef";

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void adminSeesGroupedTreeWithSecretsRedactedAndNoSecretMaterial() throws Exception {
    createUser("config-admin", UserRole.ADMIN);
    String token = token("config-admin");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/admin/configuration").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            // The tree is grouped by namespace and the JWT secret is present but redacted.
            .andExpect(jsonPath("$.groups[*].key").value(org.hamcrest.Matchers.hasItem("auth")))
            .andExpect(jsonPath("$.groups[*].key").value(org.hamcrest.Matchers.hasItem("s3")))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    // Redaction shape is present…
    assertThat(body).contains("\"qnop.auth.jwt-secret\"").contains("\"SECRET\"");
    // …and the bound secret values are nowhere in the payload.
    assertThat(body).doesNotContain(JWT_SECRET);
    assertThat(body).doesNotContain(ENCRYPTION_KEY);
    assertThat(body).doesNotContain(ENCRYPTION_SALT);
  }

  @Test
  void entriesCarryDescriptionsHarvestedFromTheConfigurationPropertiesJavadoc() throws Exception {
    createUser("config-doc-admin", UserRole.ADMIN);
    String token = token("config-doc-admin");

    // The description is generated at compile time from QnopProperties.Auth's Javadoc and joined
    // from the classpath metadata at runtime — a real end-to-end check of the metadata scan, which
    // the pure builder/parser unit tests cannot cover.
    mockMvc
        .perform(get("/api/v1/admin/configuration").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.groups[*].entries[?(@.path == 'qnop.auth.access-token-ttl')].description")
                .value(
                    org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString(
                            "lifetime of a self-issued access token"))))
        // A leaf of a nested @ConfigurationProperties record (Limit) is documented too, from the
        // Limit record's own Javadoc — guards the nested-record harvest.
        .andExpect(
            jsonPath(
                    "$.groups[*].entries[?(@.path == 'qnop.auth.rate-limit.login.max-attempts')].description")
                .value(
                    org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("burst capacity"))));
  }

  private User createUser(String username, UserRole role) {
    User user =
        User.internal(
            username, username + "@example.com", username, passwordEncoder.encode(PASSWORD));
    user.setRole(role);
    return userRepository.saveAndFlush(user);
  }

  private String token(String username) throws Exception {
    String body = "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
                    .with(
                        request -> {
                          request.setRemoteAddr("203.0.113.7");
                          return request;
                        }))
            .andExpect(status().isOk())
            .andReturn();
    Matcher matcher = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
