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
package io.qnop.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated configuration for the security/crypto foundation (issue #10). Bound
 * from the {@code qnop.*} environment namespace (ADR-0020) and validated at context startup: an
 * insecure default or missing secret fails the application fast rather than booting with weak
 * crypto.
 *
 * @param auth authentication secrets (JWT signing material, symmetric encryption key + salt)
 * @param cors cross-origin policy for the SPA and third-party API clients
 */
@ConfigurationProperties(prefix = "qnop")
@Validated
public record QnopProperties(@Valid Auth auth, @Valid Cors cors) {

  /**
   * Authentication secrets. {@code jwtSecret} is the HKDF input keying material for
   * domain-separated JWT keys; {@code encryptionKey} + {@code encryptionSalt} feed {@code
   * Encryptors.delux} for symmetric text encryption (e.g. OIDC client secrets at rest, from issue
   * #21).
   *
   * @param jwtSecret HKDF input keying material; must be a strong, non-default secret
   * @param encryptionKey password for symmetric text encryption; must be a strong, non-default
   *     secret
   * @param encryptionSalt hex-encoded salt for symmetric text encryption (Encryptors.delux)
   * @param accessTokenTtl lifetime of a self-issued access token (default 15m, issue #17)
   * @param refreshTokenTtl lifetime of a refresh token / its cookie (default 7d, issue #17)
   * @param issuer the {@code iss} claim of self-issued tokens (default {@code qnop})
   * @param cookieSecure whether the refresh cookie carries {@code Secure} (default true; set false
   *     only for local HTTP dev — browsers drop {@code Secure} cookies on plain HTTP)
   * @param oidc OIDC/OAuth2 login settings (post-login redirect base, issue #178)
   */
  public record Auth(
      @ValidSecret String jwtSecret,
      @ValidSecret String encryptionKey,
      @NotBlank
          @Pattern(
              regexp = "^[0-9a-fA-F]{16,}$",
              message =
                  "qnop.auth.encryption-salt must be a hex-encoded string of at least 16 characters")
          String encryptionSalt,
      Duration accessTokenTtl,
      Duration refreshTokenTtl,
      String issuer,
      Boolean cookieSecure,
      @Valid Oidc oidc) {

    public Auth {
      accessTokenTtl = accessTokenTtl != null ? accessTokenTtl : Duration.ofMinutes(15);
      refreshTokenTtl = refreshTokenTtl != null ? refreshTokenTtl : Duration.ofDays(7);
      issuer = (issuer == null || issuer.isBlank()) ? "qnop" : issuer;
      cookieSecure = cookieSecure == null ? Boolean.TRUE : cookieSecure;
      oidc = oidc != null ? oidc : new Oidc(null);
    }
  }

  /**
   * OIDC/OAuth2 login settings (issue #178).
   *
   * @param frontendBaseUrl absolute base URL the browser is redirected to after an OIDC login (e.g.
   *     {@code https://app.example.com}). Deliberately separate from the CORS origin list, which
   *     governs API access only. Blank (the default) means same-origin relative redirects — correct
   *     when the SPA is served from the API origin. A non-blank value must be an http(s) URL; a
   *     trailing slash is trimmed.
   */
  public record Oidc(
      @Pattern(
              regexp = "^(https?://.+)?$",
              message = "qnop.auth.oidc.frontend-base-url must be blank or an http(s) URL")
          String frontendBaseUrl) {

    public Oidc {
      frontendBaseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
      if (frontendBaseUrl.endsWith("/")) {
        frontendBaseUrl = frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1);
      }
    }
  }

  /**
   * Cross-origin resource sharing policy.
   *
   * @param allowedOrigins exact origins permitted to call the API (no wildcards with credentials)
   */
  public record Cors(List<String> allowedOrigins) {

    public Cors {
      allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
  }
}
