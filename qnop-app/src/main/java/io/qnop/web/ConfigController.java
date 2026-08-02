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
import io.qnop.api.v1.model.ServerConfigFeatures;
import io.qnop.api.v1.model.ServerConfigGeneral;
import io.qnop.api.v1.model.ServerConfigResponse;
import io.qnop.api.v1.model.ServerConfigReview;
import io.qnop.api.v1.model.ServerConfigTracking;
import io.qnop.api.v1.model.ServerConfigUpload;
import io.qnop.api.v1.model.SupportedFormat;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.banner.InfoBannerService;
import io.qnop.service.branding.BrandingService;
import io.qnop.service.branding.BrandingService.SlotStatus;
import io.qnop.service.document.DocumentRenditionService;
import io.qnop.service.document.DocumentTypeSniffer;
import io.qnop.service.limits.FeatureToggleProperties;
import io.qnop.service.oidc.OidcProviderService;
import io.qnop.service.review.AnnotationExportService;
import io.qnop.service.review.export.AnnotationExportFormat;
import io.qnop.service.tracking.TrackingConfigService;
import io.qnop.service.tracking.TrackingRuntimeConfig;
import java.util.ArrayList;
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
  private final AnnotationExportService exports;
  private final DocumentRenditionService renditions;
  private final InfoBannerService banners;
  private final TrackingConfigService tracking;
  private final FeatureToggleProperties features;

  public ConfigController(
      OidcProviderService oidcProviders,
      ApplicationSettingsService settings,
      BrandingService branding,
      AnnotationExportService exports,
      DocumentRenditionService renditions,
      InfoBannerService banners,
      TrackingConfigService tracking,
      FeatureToggleProperties features,
      ObjectProvider<BuildProperties> buildProperties) {
    this.oidcProviders = oidcProviders;
    this.settings = settings;
    this.branding = branding;
    this.exports = exports;
    this.renditions = renditions;
    this.banners = banners;
    this.tracking = tracking;
    this.features = features;
    this.buildProperties = buildProperties.getIfAvailable();
  }

  @Override
  public ResponseEntity<ServerConfigResponse> getServerConfig() {
    ServerConfigResponse body =
        new ServerConfigResponse()
            .version(resolveVersion(buildProperties))
            .edition(Edition.COMMUNITY)
            .general(
                new ServerConfigGeneral()
                    .siteName("qnop")
                    .defaultTimezone(
                        settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE)))
            .auth(
                new ServerConfigAuth()
                    .oidcProviders(enabledOidcProviders())
                    .selfRegistrationEnabled(settings.selfRegistrationEnabled()))
            .review(
                new ServerConfigReview()
                    .freeReattachEnabled(
                        settings.getBoolean(ApplicationSettingKey.REVIEW_FREE_REATTACH_ENABLED))
                    .finalizeWithOpenAnnotations(
                        settings.getBoolean(
                            ApplicationSettingKey.REVIEW_FINALIZE_WITH_OPEN_ANNOTATIONS)))
            .upload(new ServerConfigUpload().maxDocumentSizeMb(DEFAULT_MAX_DOCUMENT_SIZE_MB))
            // Only what this server can actually produce: PDF needs an office
            // converter, and a client must not offer a download that would fail.
            .exportFormats(
                exports.availableFormats().stream().map(AnnotationExportFormat::getId).toList())
            // Only what this server can actually ingest, so a client never offers an
            // upload the pipeline would reject with 415 (issue #345). PDF always; Word
            // only where an office converter is installed, because that is what turns a
            // DOCX into something the viewer can render (issue #343, ADR-0010). Markdown
            // joins once its extractor lands; further formats are an Enterprise feature.
            .features(
                new ServerConfigFeatures()
                    .oidc(features.oidc())
                    .annotationExport(features.annotationExport())
                    .customBranding(features.customBranding())
                    .smtpConfiguration(features.smtpConfiguration())
                    .emailTemplates(features.emailTemplates())
                    .deploymentConfiguration(features.deploymentConfiguration()))
            .supportedFormats(supportedFormats())
            .branding(buildBranding());
    // The sign-in notice (issue #664) belongs in the one response the login
    // screen already fetches, and it is public because that screen is. The
    // in-app banner deliberately does NOT ride along here — it is an
    // authenticated read (GET /banner), so a deployment's troubles are not
    // announced to anonymous callers.
    banners.signIn().map(BannerMapper::toApi).ifPresent(body::banner);
    // Absent unless an operator configured measurement AND completed it (issue
    // #666) — a client that sees no tracking block loads no script at all.
    tracking.current().map(ConfigController::toApi).ifPresent(body::tracking);
    return ResponseEntity.ok(body);
  }

  /** What the browser needs to measure usage; the analytics host stays server-side. */
  private static ServerConfigTracking toApi(TrackingRuntimeConfig config) {
    return new ServerConfigTracking()
        .provider(ServerConfigTracking.ProviderEnum.fromValue(config.provider().id()))
        .siteId(config.siteId())
        .consentRequired(config.consentRequired())
        .respectDnt(config.respectDnt())
        .trackPrivilegedRoles(config.trackPrivilegedRoles());
  }

  /** The document formats this deployment can take, not the ones the release knows about. */
  private List<SupportedFormat> supportedFormats() {
    List<SupportedFormat> formats = new ArrayList<>();
    formats.add(SupportedFormat.PDF);
    if (renditions.supports(DocumentTypeSniffer.DOCX)) {
      formats.add(SupportedFormat.DOCX);
    }
    return formats;
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
