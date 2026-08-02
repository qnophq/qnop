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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.banner.InfoBannerService;
import io.qnop.service.branding.BrandingService;
import io.qnop.service.branding.BrandingService.BrandingSource;
import io.qnop.service.branding.BrandingService.SlotStatus;
import io.qnop.service.document.DocumentRenditionService;
import io.qnop.service.limits.FeatureToggleProperties;
import io.qnop.service.oidc.OidcProviderLoginView;
import io.qnop.service.oidc.OidcProviderService;
import io.qnop.service.review.AnnotationExportService;
import io.qnop.service.review.export.AnnotationExportFormat;
import io.qnop.service.tracking.TrackingConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link ConfigController}. Proves the OpenAPI-first toolchain end-to-end: the
 * controller implements the generated {@code ServerConfigApi} interface, is mounted under the
 * {@code /api/v1} prefix from {@link ApiPathConfig}, and serializes the generated {@code
 * ServerConfigResponse} DTO (ADR-0021). No database, so no Testcontainers/Docker is needed.
 */
// QnopApplication lives in io.qnop.bootstrap (a sibling, not a parent, of this
// package — ADR-0004), so the slice cannot auto-discover the @SpringBootConfiguration.
// Configure the context explicitly with just the controller and the path-prefix config.
@WebMvcTest
@ContextConfiguration(
    classes = {ConfigController.class, ApiPathConfig.class, ConfigControllerTest.Features.class})
class ConfigControllerTest {

  /**
   * A real value rather than a mock: the capability flags are part of what this endpoint publishes,
   * and a mocked record answers false to everything — which would assert the opposite of the
   * Community default (issue #674).
   */
  @org.springframework.boot.test.context.TestConfiguration
  static class Features {
    @org.springframework.context.annotation.Bean
    FeatureToggleProperties featureToggles() {
      return FeatureToggleProperties.all();
    }
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OidcProviderService oidcProviders;
  @MockitoBean private ApplicationSettingsService settings;
  @MockitoBean private BrandingService branding;
  @MockitoBean private AnnotationExportService exports;
  @MockitoBean private DocumentRenditionService renditions;
  // Unstubbed, so it answers Optional.empty(): no sign-in banner configured is
  // the default this endpoint's shape is asserted against (issue #664). The
  // banner's own behaviour is covered by BannerApiIT and InfoBannerServiceTest.
  @MockitoBean private InfoBannerService banners;
  // Unstubbed too: no tracking configured is the shape this endpoint is asserted
  // against (issue #666); TrackingConfigServiceTest covers the resolution itself.
  @MockitoBean private TrackingConfigService tracking;

  @BeforeEach
  void setUp() {
    // No providers configured: auth.oidcProviders should serialize as an empty list.
    when(oidcProviders.enabledLoginViews()).thenReturn(List.of());
    when(settings.getBoolean(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED))
        .thenReturn(false);
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE))
        .thenReturn("Europe/Berlin");
    // The two formats that need nothing installed; PDF depends on an office
    // converter this deployment may not have (issue #639).
    when(exports.availableFormats())
        .thenReturn(List.of(AnnotationExportFormat.XLSX, AnnotationExportFormat.DOCX));
    // All slots on the factory default until something is uploaded.
    when(branding.statusAll())
        .thenReturn(
            List.of(
                new SlotStatus("logo-light", BrandingSource.DEFAULT, "sha-light"),
                new SlotStatus("logo-dark", BrandingSource.DEFAULT, "sha-dark"),
                new SlotStatus("logomark", BrandingSource.DEFAULT, "sha-mark")));
  }

  @Test
  @DisplayName("GET /api/v1/config returns the public Community configuration")
  void getConfig_returnsCommunityConfig() throws Exception {
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.edition").value("COMMUNITY"))
        .andExpect(jsonPath("$.version").exists())
        .andExpect(jsonPath("$.general.siteName").value("qnop"))
        .andExpect(jsonPath("$.general.defaultTimezone").value("Europe/Berlin"))
        .andExpect(jsonPath("$.auth.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.auth.oidcProviders").isArray())
        .andExpect(jsonPath("$.auth.oidcProviders").isEmpty())
        .andExpect(jsonPath("$.upload.maxDocumentSizeMb").value(50))
        // Community ingests PDF only — the list must not advertise unsupported formats (issue
        // #345).
        // PDF always; Word only where a converter is installed, which this mock is
        // not (issue #343) — the server's capability, not the release's.
        .andExpect(jsonPath("$.supportedFormats.length()").value(1))
        .andExpect(jsonPath("$.supportedFormats[0]").value("PDF"))
        .andExpect(jsonPath("$.branding.logoLight.source").value("DEFAULT"))
        .andExpect(
            jsonPath("$.branding.logoLight.url").value("/api/v1/branding/logo-light?v=sha-light"))
        .andExpect(jsonPath("$.branding.logomark.source").value("DEFAULT"))
        // Community has every capability; a deployment that says nothing about
        // features must not appear to have lost them (issue #674).
        .andExpect(jsonPath("$.features.oidc").value(true))
        .andExpect(jsonPath("$.features.annotationExport").value(true))
        .andExpect(jsonPath("$.features.customBranding").value(true));
  }

  @Test
  @DisplayName("selfRegistrationEnabled reflects the effective answer, not the raw setting")
  void getConfig_reflectsSelfRegistrationSetting() throws Exception {
    // The effective value folds in whether this deployment offers self-registration
    // at all (issue #681), so the config and the endpoint cannot disagree about
    // whether a sign-up link should appear.
    when(settings.selfRegistrationEnabled()).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auth.selfRegistrationEnabled").value(true));
  }

  @Test
  @DisplayName("review.finalizeWithOpenAnnotations reflects the application setting (#568)")
  void getConfig_reflectsFinalizeWithOpenSetting() throws Exception {
    when(settings.getBoolean(ApplicationSettingKey.REVIEW_FINALIZE_WITH_OPEN_ANNOTATIONS))
        .thenReturn(true);

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.review.finalizeWithOpenAnnotations").value(true));
  }

  @Test
  @DisplayName("review.freeReattachEnabled reflects the application setting (#562)")
  void getConfig_reflectsFreeReattachSetting() throws Exception {
    when(settings.getBoolean(ApplicationSettingKey.REVIEW_FREE_REATTACH_ENABLED)).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.review.freeReattachEnabled").value(true));
  }

  @Test
  @DisplayName("enabled providers expose icon kind and account-switch affordances")
  void getConfig_mapsOidcLoginInfoFields() throws Exception {
    String googleLogin = "/oauth2/authorization/11111111-1111-1111-1111-111111111111";
    String githubLogin = "/oauth2/authorization/22222222-2222-2222-2222-222222222222";
    when(oidcProviders.enabledLoginViews())
        .thenReturn(
            List.of(
                new OidcProviderLoginView(
                    "11111111-1111-1111-1111-111111111111",
                    "Google",
                    googleLogin,
                    "google",
                    googleLogin + "?prompt=select_account",
                    null),
                new OidcProviderLoginView(
                    "22222222-2222-2222-2222-222222222222",
                    "GitHub",
                    githubLogin,
                    "github",
                    null,
                    "https://github.com/logout")));

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auth.oidcProviders[0].name").value("Google"))
        .andExpect(jsonPath("$.auth.oidcProviders[0].iconKind").value("google"))
        .andExpect(
            jsonPath("$.auth.oidcProviders[0].accountPickerLoginUrl")
                .value(googleLogin + "?prompt=select_account"))
        .andExpect(jsonPath("$.auth.oidcProviders[1].iconKind").value("github"))
        .andExpect(
            jsonPath("$.auth.oidcProviders[1].accountSwitchHintUrl")
                .value("https://github.com/logout"));
  }

  @Test
  @DisplayName("the config endpoint is only reachable under the /api/v1 prefix")
  void getConfig_withoutVersionPrefix_isNotFound() throws Exception {
    mockMvc.perform(get("/config")).andExpect(status().isNotFound());
  }
}
