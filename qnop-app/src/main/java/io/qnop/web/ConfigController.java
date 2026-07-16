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
import io.qnop.api.v1.model.OidcIconKind;
import io.qnop.api.v1.model.OidcProviderLoginInfo;
import io.qnop.api.v1.model.ServerConfigAuth;
import io.qnop.api.v1.model.ServerConfigBranding;
import io.qnop.api.v1.model.ServerConfigBrandingSlot;
import io.qnop.api.v1.model.ServerConfigGeneral;
import io.qnop.api.v1.model.ServerConfigResponse;
import io.qnop.api.v1.model.ServerConfigUpload;
import io.qnop.api.v1.model.SupportedFormat;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.branding.BrandingService;
import io.qnop.service.branding.BrandingService.SlotStatus;
import io.qnop.service.oidc.OidcProviderService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
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
  private final BrandingService branding;
  private final BuildProperties buildProperties;

  public ConfigController(
      OidcProviderService oidcProviders,
      ApplicationSettingsService settings,
      BrandingService branding,
      ObjectProvider<BuildProperties> buildProperties) {
    this.oidcProviders = oidcProviders;
    this.settings = settings;
    this.branding = branding;
    this.buildProperties = buildProperties.getIfAvailable();
  }

  @Override
  public ResponseEntity<ServerConfigResponse> getServerConfig() {
    ServerConfigResponse body =
        new ServerConfigResponse()
            .version(resolveVersion(buildProperties))
            .edition(Edition.COMMUNITY)
            .general(new ServerConfigGeneral().siteName("qnop").defaultTimezone("UTC"))
            .auth(
                new ServerConfigAuth()
                    .oidcProviders(enabledOidcProviders())
                    .selfRegistrationEnabled(
                        settings.getBoolean(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED)))
            .upload(new ServerConfigUpload().maxDocumentSizeMb(DEFAULT_MAX_DOCUMENT_SIZE_MB))
            // Report only the formats whose extractor actually ships, so a client never offers an
            // upload the ingest pipeline would reject with 415 (magic-byte sniffing, issue #345).
            // Today that is PDF; DOCX and Markdown are Community-scope and join this list once
            // their
            // extractors land (further formats are an Enterprise feature).
            .supportedFormats(List.of(SupportedFormat.PDF))
            .branding(buildBranding());
    return ResponseEntity.ok(body);
  }

  /** Effective branding (custom vs default) per slot, so the SPA can render and badge each logo. */
  private ServerConfigBranding buildBranding() {
    Map<String, SlotStatus> bySlot =
        branding.statusAll().stream()
            .collect(Collectors.toMap(SlotStatus::slot, Function.identity()));
    return new ServerConfigBranding()
        .logoLight(toBrandingSlot(bySlot.get("logo-light")))
        .logoDark(toBrandingSlot(bySlot.get("logo-dark")))
        .logomark(toBrandingSlot(bySlot.get("logomark")));
  }

  private static ServerConfigBrandingSlot toBrandingSlot(SlotStatus status) {
    return new ServerConfigBrandingSlot()
        .source(ServerConfigBrandingSlot.SourceEnum.fromValue(status.source().name()))
        .url("/api/v1/branding/" + status.slot() + "?v=" + status.version());
  }

  /** The enabled providers as login buttons for the SPA (issue #21), with icon + account-switch. */
  private List<OidcProviderLoginInfo> enabledOidcProviders() {
    return oidcProviders.enabledLoginViews().stream()
        .map(
            v ->
                new OidcProviderLoginInfo()
                    .id(v.id())
                    .name(v.name())
                    .loginUrl(v.loginUrl())
                    .iconKind(OidcIconKind.fromValue(v.iconKind()))
                    .accountPickerLoginUrl(v.accountPickerLoginUrl())
                    .accountSwitchHintUrl(v.accountSwitchHintUrl()))
        .toList();
  }

  /** Reads the server version from the JAR manifest, falling back to {@code "unknown"}. */
  /**
   * The running version, from Boot's build-info.properties (stamped by {@code springBoot {
   * buildInfo() }}, issue #495) with the jar manifest as fallback — "unknown" only when running
   * from exploded classes without build info (e.g. IDE runs).
   */
  private static String resolveVersion(BuildProperties buildProperties) {
    if (buildProperties != null && buildProperties.getVersion() != null) {
      return buildProperties.getVersion();
    }
    return Optional.ofNullable(ConfigController.class.getPackage().getImplementationVersion())
        .orElse(UNKNOWN_VERSION);
  }
}
