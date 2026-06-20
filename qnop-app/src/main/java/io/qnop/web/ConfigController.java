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

import io.qnop.api.v1.endpoint.ServerConfigApi;
import io.qnop.api.v1.model.Edition;
import io.qnop.api.v1.model.OidcProviderLoginInfo;
import io.qnop.api.v1.model.ServerConfigAuth;
import io.qnop.api.v1.model.ServerConfigGeneral;
import io.qnop.api.v1.model.ServerConfigResponse;
import io.qnop.api.v1.model.ServerConfigUpload;
import io.qnop.api.v1.model.SupportedFormat;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.oidc.OidcProviderService;
import io.qnop.service.oidc.OidcProviderView;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public server configuration endpoint ({@code GET /api/v1/config}), implementing the generated
 * {@link ServerConfigApi} contract (ADR-0015, ADR-0021).
 *
 * <p><strong>Partly placeholder (issue #9/#99).</strong> This endpoint proves the OpenAPI-first
 * toolchain end-to-end: a hand-written {@code @RestController} implements a generated interface and
 * returns a generated DTO. {@code auth.oidcProviders} comes from the OIDC registry (#21) and {@code
 * auth.selfRegistrationEnabled} from application settings (#99); {@code edition} ({@code
 * EditionResolver} SPI, ADR-0012) and {@code general}/{@code upload} (#16) are still static.
 */
@RestController
public class ConfigController implements ServerConfigApi {

  /** Static Community fallback until the upload limit is operator-configurable (#16). */
  private static final int DEFAULT_MAX_DOCUMENT_SIZE_MB = 50;

  /** Reported when no build manifest is available (e.g. when running from exploded classes). */
  private static final String UNKNOWN_VERSION = "unknown";

  private final OidcProviderService oidcProviders;
  private final ApplicationSettingsService settings;

  public ConfigController(OidcProviderService oidcProviders, ApplicationSettingsService settings) {
    this.oidcProviders = oidcProviders;
    this.settings = settings;
  }

  @Override
  public ResponseEntity<ServerConfigResponse> getServerConfig() {
    ServerConfigResponse body =
        new ServerConfigResponse()
            .version(resolveVersion())
            .edition(Edition.COMMUNITY)
            .general(new ServerConfigGeneral().siteName("qnop").defaultTimezone("UTC"))
            .auth(
                new ServerConfigAuth()
                    .oidcProviders(enabledOidcProviders())
                    .selfRegistrationEnabled(
                        settings.getBoolean(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED)))
            .upload(new ServerConfigUpload().maxDocumentSizeMb(DEFAULT_MAX_DOCUMENT_SIZE_MB))
            .supportedFormats(
                List.of(SupportedFormat.PDF, SupportedFormat.DOCX, SupportedFormat.MD));
    return ResponseEntity.ok(body);
  }

  /** The enabled providers as login buttons for the SPA (issue #21). */
  private List<OidcProviderLoginInfo> enabledOidcProviders() {
    return oidcProviders.findAll().stream()
        .filter(OidcProviderView::enabled)
        .map(
            v ->
                new OidcProviderLoginInfo()
                    .id(v.id().toString())
                    .name(v.name())
                    .loginUrl("/oauth2/authorization/" + v.id()))
        .toList();
  }

  /** Reads the server version from the JAR manifest, falling back to {@code "unknown"}. */
  private static String resolveVersion() {
    return Optional.ofNullable(ConfigController.class.getPackage().getImplementationVersion())
        .orElse(UNKNOWN_VERSION);
  }
}
