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
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
   * domain-separated JWT keys; {@code encryptionKey} + {@code encryptionSalt} key the {@link
   * SecretCipher} for secrets at rest (e.g. OIDC client secrets, the SMTP password). The optional
   * {@code previousEncryptionKey} + {@code previousEncryptionSalt} are accepted for decryption
   * only, so the encryption key can be rotated (issue #927, ADR-0022 amendment).
   *
   * @param jwtSecret HKDF input keying material; must be a strong, non-default secret
   * @param encryptionKey password for symmetric text encryption; must be a strong, non-default
   *     secret
   * @param encryptionSalt hex-encoded salt for symmetric text encryption (HKDF salt; also the salt
   *     of the legacy pre-#927 format)
   * @param accessTokenTtl lifetime of a self-issued access token (default 15m, issue #17)
   * @param refreshTokenTtl lifetime of a refresh token / its cookie (default 7d, issue #17)
   * @param issuer the {@code iss} claim of self-issued tokens (default {@code qnop})
   * @param cookieSecure whether the refresh cookie carries {@code Secure} (default true; set false
   *     only for local HTTP dev — browsers drop {@code Secure} cookies on plain HTTP)
   * @param oidc OIDC/OAuth2 login settings (post-login redirect base, issue #178)
   * @param previousEncryptionKey the encryption key being rotated out, accepted for decryption
   *     only; blank (the default) when no rotation is in progress (issue #927)
   * @param previousEncryptionSalt hex-encoded salt belonging to the previous encryption key; blank
   *     means it shares the current salt (issue #927)
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
      @Valid Oidc oidc,
      String previousEncryptionKey,
      @Pattern(
              regexp = "^([0-9a-fA-F]{16,})?$",
              message =
                  "qnop.auth.previous-encryption-salt must be blank or a hex-encoded string of at"
                      + " least 16 characters")
          String previousEncryptionSalt) {

    /** The pre-#927 shape, with no previous (rotation) key configured. */
    public Auth(
        String jwtSecret,
        String encryptionKey,
        String encryptionSalt,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String issuer,
        Boolean cookieSecure,
        Oidc oidc) {
      this(
          jwtSecret,
          encryptionKey,
          encryptionSalt,
          accessTokenTtl,
          refreshTokenTtl,
          issuer,
          cookieSecure,
          oidc,
          null,
          null);
    }

    /** The canonical constructor is the one bound — the record has a convenience overload. */
    @ConstructorBinding
    public Auth {
      accessTokenTtl = accessTokenTtl != null ? accessTokenTtl : Duration.ofMinutes(15);
      refreshTokenTtl = refreshTokenTtl != null ? refreshTokenTtl : Duration.ofDays(7);
      issuer = (issuer == null || issuer.isBlank()) ? "qnop" : issuer;
      cookieSecure = cookieSecure == null ? Boolean.TRUE : cookieSecure;
      oidc = oidc != null ? oidc : new Oidc(null);
      previousEncryptionKey = previousEncryptionKey == null ? "" : previousEncryptionKey.trim();
      previousEncryptionSalt = previousEncryptionSalt == null ? "" : previousEncryptionSalt.trim();
    }

    /** Whether a previous (rotation) encryption key is configured. */
    public boolean hasPreviousEncryptionKey() {
      return !previousEncryptionKey.isEmpty();
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
