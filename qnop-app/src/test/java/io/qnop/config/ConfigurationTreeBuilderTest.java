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

import io.qnop.api.v1.model.ConfigurationEntry;
import io.qnop.api.v1.model.ConfigurationGroup;
import io.qnop.api.v1.model.ConfigurationResponse;
import io.qnop.api.v1.model.ConfigurationValueType;
import io.qnop.security.QnopProperties;
import io.qnop.service.config.ConfigurationTreeBuilder;
import io.qnop.service.http.HttpClientProperties;
import io.qnop.service.review.ReanchoringProperties;
import io.qnop.service.storage.S3Properties;
import io.qnop.web.security.ratelimit.RateLimitProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the effective-config flattening + secret redaction (issue #522). Drives the
 * builder with the real property records — no Spring — so the kebab paths, grouping, env-var
 * derivation and (above all) the terminal-token redaction are pinned exactly as the endpoint serves
 * them. The secret values below are deliberately recognisable so the "no secret material" assertion
 * is real. Lives in qnop-app because {@link RateLimitProperties} does (it exercises the
 * rate-limit-folds-into- auth case), but the builder under test is pure qnop-core logic.
 */
class ConfigurationTreeBuilderTest {

  private static final String JWT_SECRET = "SUPER-SECRET-jwt-value";
  private static final String ENCRYPTION_KEY = "SUPER-SECRET-encryption-value";
  private static final String ENCRYPTION_SALT = "0123456789abcdef0123456789abcdef";
  private static final String S3_ACCESS_KEY = "SUPER-SECRET-access-value";
  private static final String S3_SECRET_KEY = "SUPER-SECRET-s3-value";

  private final ConfigurationTreeBuilder builder = new ConfigurationTreeBuilder();

  private ConfigurationResponse build(String jwtSecretOverride) {
    QnopProperties qnop =
        new QnopProperties(
            new QnopProperties.Auth(
                jwtSecretOverride != null ? jwtSecretOverride : JWT_SECRET,
                ENCRYPTION_KEY,
                ENCRYPTION_SALT,
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                "qnop",
                Boolean.TRUE,
                new QnopProperties.Oidc("https://app.example.com")),
            new QnopProperties.Cors(List.of("https://a.example.com", "https://b.example.com")));
    S3Properties s3 =
        new S3Properties(
            null,
            "us-east-1",
            "qnop-bucket",
            S3_ACCESS_KEY,
            S3_SECRET_KEY,
            Boolean.TRUE,
            Boolean.FALSE,
            Duration.ofHours(1),
            Duration.ofSeconds(60),
            Duration.ofSeconds(20));
    HttpClientProperties http = new HttpClientProperties(null, null, null, null, null);
    ReanchoringProperties reanchoring = ReanchoringProperties.defaults();
    RateLimitProperties rateLimit =
        new RateLimitProperties(List.of("10.0.0.0/8"), null, null, null, null, null, 100_000);

    SequencedMap<String, Object> roots = new LinkedHashMap<>();
    roots.put("qnop", qnop);
    roots.put("qnop.auth.rate-limit", rateLimit);
    roots.put("qnop.http-client", http);
    roots.put("qnop.reanchoring", reanchoring);
    roots.put("qnop.s3", s3);
    return builder.build(roots);
  }

  private ConfigurationEntry entry(ConfigurationResponse response, String path) {
    return response.getGroups().stream()
        .flatMap(group -> group.getEntries().stream())
        .filter(candidate -> candidate.getPath().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no entry for " + path));
  }

  @Test
  void redactsEverySecretByTerminalTokenAndNeverEmitsItsValue() {
    ConfigurationResponse response = build(null);

    for (String secretPath :
        List.of(
            "qnop.auth.jwt-secret",
            "qnop.auth.encryption-key",
            "qnop.auth.encryption-salt",
            "qnop.s3.access-key",
            "qnop.s3.secret-key")) {
      ConfigurationEntry secret = entry(response, secretPath);
      assertThat(secret.getValueType()).isEqualTo(ConfigurationValueType.SECRET);
      assertThat(secret.getValue()).isNull();
      assertThat(secret.getConfigured()).isTrue();
    }

    // No secret material anywhere in the rendered values — the whole point of the endpoint.
    List<String> allValues =
        response.getGroups().stream()
            .flatMap(group -> group.getEntries().stream())
            .map(ConfigurationEntry::getValue)
            .filter(value -> value != null)
            .toList();
    assertThat(allValues)
        .noneMatch(
            value ->
                value.contains(JWT_SECRET)
                    || value.contains(ENCRYPTION_KEY)
                    || value.contains(ENCRYPTION_SALT)
                    || value.contains(S3_ACCESS_KEY)
                    || value.contains(S3_SECRET_KEY));
  }

  @Test
  void configuredIsFalseForABlankSecret() {
    ConfigurationResponse response = build("   ");
    ConfigurationEntry jwt = entry(response, "qnop.auth.jwt-secret");
    assertThat(jwt.getValueType()).isEqualTo(ConfigurationValueType.SECRET);
    assertThat(jwt.getConfigured()).isFalse();
  }

  @Test
  void keepsNonSecretLeavesThatMerelyContainSecretWords() {
    ConfigurationResponse response = build(null);
    // Not a secret: terminal token is "secure"/"bucket"/"url", not in the redaction set.
    assertThat(entry(response, "qnop.auth.cookie-secure").getValueType())
        .isEqualTo(ConfigurationValueType.BOOLEAN);
    assertThat(entry(response, "qnop.s3.bucket").getValue()).isEqualTo("qnop-bucket");
    assertThat(entry(response, "qnop.auth.oidc.frontend-base-url").getValue())
        .isEqualTo("https://app.example.com");
  }

  @Test
  void rendersValueTypesForRendering() {
    ConfigurationResponse response = build(null);
    assertThat(entry(response, "qnop.auth.access-token-ttl").getValueType())
        .isEqualTo(ConfigurationValueType.DURATION);
    assertThat(entry(response, "qnop.auth.access-token-ttl").getValue()).isEqualTo("PT15M");
    assertThat(entry(response, "qnop.auth.cookie-secure").getValue()).isEqualTo("true");
    assertThat(entry(response, "qnop.reanchoring.similarity-threshold").getValueType())
        .isEqualTo(ConfigurationValueType.NUMBER);
    assertThat(entry(response, "qnop.s3.endpoint").getValueType())
        .isEqualTo(ConfigurationValueType.UNSET);
    ConfigurationEntry origins = entry(response, "qnop.cors.allowed-origins");
    assertThat(origins.getValueType()).isEqualTo(ConfigurationValueType.LIST);
    assertThat(origins.getValue()).isEqualTo("https://a.example.com, https://b.example.com");
  }

  @Test
  void derivesKebabPathsAndEnvVarNames() {
    ConfigurationResponse response = build(null);
    assertThat(entry(response, "qnop.s3.access-key").getEnvVar()).isEqualTo("QNOP_S3_ACCESS_KEY");
    assertThat(entry(response, "qnop.auth.access-token-ttl").getEnvVar())
        .isEqualTo("QNOP_AUTH_ACCESS_TOKEN_TTL");
    assertThat(entry(response, "qnop.s3.path-style-access").getEnvVar())
        .isEqualTo("QNOP_S3_PATH_STYLE_ACCESS");
  }

  @Test
  void groupsByTopLevelNamespaceAndFoldsRateLimitIntoAuth() {
    ConfigurationResponse response = build(null);
    assertThat(response.getGroups()).extracting(ConfigurationGroup::getKey).contains("auth", "s3");

    ConfigurationGroup auth =
        response.getGroups().stream()
            .filter(group -> group.getKey().equals("auth"))
            .findFirst()
            .orElseThrow();
    // The rate-limit bean has prefix qnop.auth.rate-limit, so it lands in the auth group.
    assertThat(auth.getEntries())
        .extracting(ConfigurationEntry::getPath)
        .contains("qnop.auth.jwt-secret", "qnop.auth.rate-limit.login.max-attempts");
    assertThat(entry(response, "qnop.auth.rate-limit.login.max-attempts").getValueType())
        .isEqualTo(ConfigurationValueType.NUMBER);
  }
}
