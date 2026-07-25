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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end guard for the effective-configuration endpoint (issue #522). Proves — through the real
 * security chain and the real bound {@code @ConfigurationProperties} beans — that an ADMIN sees the
 * grouped, redacted tree and that <em>no</em> secret value ever reaches the wire. The secret values
 * asserted absent are exactly the ones {@link AbstractIntegrationTest} binds into the test context,
 * so a regression that leaked a bound secret would fail here.
 *
 * <p><strong>Incremental-compilation caveat (issue #610).</strong> Property descriptions are
 * harvested from Javadoc by {@code spring-boot-configuration-processor} at <em>compile time</em>.
 * On an incremental compile the processor rewrites {@code
 * META-INF/spring-configuration-metadata.json} for the whole module but can only read the Javadoc
 * of the sources javac actually recompiled, so every property whose declaring file was untouched
 * comes back with {@code description: null} — touching one unrelated file in {@code qnop-core} is
 * enough to drop all 35. (The same caveat applies to a locally-run {@code bootRun}: the tooltips on
 * /admin/configuration are only complete after a full compile.) The description assertions below
 * are therefore written against <em>what this compile actually harvested</em> rather than against a
 * fixed expectation, so they stay honest under a full build (always the case in CI, which compiles
 * a fresh checkout) without turning every incremental local {@code ./gradlew build} into a false
 * positive.
 */
@AutoConfigureMockMvc
@Transactional
class ConfigurationControllerIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery";
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A property of the top-level auth record, and one of a nested record (Limit). */
  private static final String ACCESS_TOKEN_TTL = "qnop.auth.access-token-ttl";

  private static final String LOGIN_MAX_ATTEMPTS = "qnop.auth.rate-limit.login.max-attempts";

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
  void everyDescriptionThisCompileHarvestedReachesTheWire() throws Exception {
    createUser("config-doc-admin", UserRole.ADMIN);
    Map<String, String> served = servedDescriptions(token("config-doc-admin"));

    // Structural, build-mode independent: both documented paths are leaves of the served tree —
    // the nested @ConfigurationProperties record (Limit) among them.
    assertThat(served).containsKeys(ACCESS_TOKEN_TTL, LOGIN_MAX_ATTEMPTS);

    // The end-to-end part this IT uniquely covers: compile-time Javadoc → classpath metadata →
    // response, joined by property path. Asserted for whatever this compile harvested, which is
    // every qnop property after a full build and a subset (possibly empty) after an incremental
    // one — the path→description join itself is pinned build-independently by
    // ConfigurationTreeBuilderTest.
    Map<String, String> harvested = harvestedDescriptions();
    assertThat(harvested.keySet())
        .allSatisfy(
            path ->
                assertThat(served)
                    .as("a harvested description must not be dropped on the way out: %s", path)
                    .doesNotContainEntry(path, null));
  }

  @Test
  void authPropertyDescriptionsReadAsTheirJavadoc() throws Exception {
    Map<String, String> harvested = harvestedDescriptions();
    assumeTrue(
        harvested.containsKey(ACCESS_TOKEN_TTL) && harvested.containsKey(LOGIN_MAX_ATTEMPTS),
        """
        No compile-time descriptions for the asserted properties — this is an incremental compile \
        that did not recompile QnopProperties/RateLimitProperties (see the class Javadoc, issue \
        #610). Run ./gradlew :qnop-core:compileJava :qnop-app:compileJava --rerun-tasks to assert \
        the content.""");

    createUser("config-javadoc-admin", UserRole.ADMIN);
    Map<String, String> served = servedDescriptions(token("config-javadoc-admin"));

    assertThat(served.get(ACCESS_TOKEN_TTL)).contains("lifetime of a self-issued access token");
    // A leaf of a nested @ConfigurationProperties record (Limit), documented from the Limit
    // record's own Javadoc — guards the nested-record harvest.
    assertThat(served.get(LOGIN_MAX_ATTEMPTS)).contains("burst capacity");
  }

  /** Property path → description as the endpoint serves it; a missing description maps to null. */
  private Map<String, String> servedDescriptions(String token) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/admin/configuration").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    Map<String, String> descriptions = new LinkedHashMap<>();
    JsonNode groups = JSON.readTree(result.getResponse().getContentAsString()).path("groups");
    for (JsonNode group : groups) {
      for (JsonNode entry : group.path("entries")) {
        descriptions.put(entry.path("path").asString(), entry.path("description").asString(null));
      }
    }
    return descriptions;
  }

  /**
   * Property path → description as <em>this compile</em> wrote it into the classpath metadata,
   * limited to the {@code qnop} namespace (dependencies ship their own metadata documents, and the
   * endpoint serves only our own properties). Parsed here independently of the production {@code
   * ConfigurationMetadata} parser so the expectation is derived from the file, not from the code
   * under test.
   */
  private static Map<String, String> harvestedDescriptions() throws IOException {
    Map<String, String> descriptions = new LinkedHashMap<>();
    Resource[] resources =
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/spring-configuration-metadata.json");
    for (Resource resource : resources) {
      String document;
      try (var input = resource.getInputStream()) {
        document = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
      }
      for (JsonNode property : JSON.readTree(document).path("properties")) {
        String name = property.path("name").asString(null);
        String description = property.path("description").asString(null);
        if (name != null && name.startsWith("qnop.") && description != null) {
          descriptions.putIfAbsent(name, description);
        }
      }
    }
    return descriptions;
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
