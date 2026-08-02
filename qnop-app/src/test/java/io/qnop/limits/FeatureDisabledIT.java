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
package io.qnop.limits;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.ApplicationSetting;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.ApplicationSettingRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * A deployment with all three capabilities withheld (issue #674).
 *
 * <p>The assertions that matter are the ones past the buttons: a client that never sees an option
 * is easy, and a URL somebody types is the real test. Every case here goes at the endpoint
 * directly.
 */
@TestPropertySource(
    properties = {
      "qnop.features.oidc=false",
      "qnop.features.annotation-export=false",
      "qnop.features.custom-branding=false",
      "qnop.features.scheduler-manual-run=false",
      "qnop.features.scheduler-job-settings=false",
      "qnop.features.smtp-configuration=false",
      "qnop.features.email-templates=false",
      "qnop.features.usage-tracking=false",
      "qnop.features.upload-constraints=false",
      "qnop.features.self-registration=false",
      "qnop.features.deployment-configuration=false",
      // A quota alongside, to pin down that hiding the screen does not stop the
      // machinery behind it. The seed holds more than one account already, so
      // this ceiling is already full.
      "qnop.limits.max-users=1"
    })
class FeatureDisabledIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private ApplicationSettingRepository applicationSettings;
  @Autowired private ApplicationSettingsService settings;
  @Autowired private DocumentVersionRepository versions;

  @Test
  @DisplayName("the public config offers none of the withheld capabilities")
  void configReportsWhatIsMissing() throws Exception {
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.features.oidc").value(false))
        .andExpect(jsonPath("$.features.annotationExport").value(false))
        .andExpect(jsonPath("$.features.customBranding").value(false))
        .andExpect(jsonPath("$.features.smtpConfiguration").value(false))
        .andExpect(jsonPath("$.features.emailTemplates").value(false))
        // The lists agree with the flags: a client reading either one reaches the
        // same conclusion.
        .andExpect(jsonPath("$.auth.oidcProviders").isEmpty())
        .andExpect(jsonPath("$.exportFormats").isEmpty());
  }

  @Test
  @DisplayName("the OAuth2 entry point is not mounted, not merely hidden")
  void oidcEndpointIsGone() throws Exception {
    // The buttons come from /config, but this URL is something a person can type
    // — and with the filter chain unregistered it is no longer a login at all.
    mockMvc
        .perform(get("/oauth2/authorization/" + UUID.randomUUID()))
        .andExpect(
            result -> {
              int status = result.getResponse().getStatus();
              org.assertj.core.api.Assertions.assertThat(status)
                  .as("an OAuth2 handshake must not start on a deployment without SSO")
                  .isNotEqualTo(302);
            });
  }

  @Test
  @DisplayName("administering providers is refused, since there is nothing to configure")
  void oidcAdministrationIsRefused() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                // A valid body on purpose: the refusal has to come from the
                // capability being off, not from a field the request forgot.
                .content(
                    "{\"name\":\"Acme\",\"providerType\":\"OIDC\","
                        + "\"issuerUri\":\"https://acme.example\","
                        + "\"clientId\":\"id\",\"clientSecret\":\"secret\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OIDC_DISABLED"));
  }

  @Test
  @DisplayName("the export endpoint refuses, not just the format list")
  void exportEndpointIsRefused() throws Exception {
    UUID documentId = seedReview();

    mockMvc
        .perform(
            get("/api/v1/documents/" + documentId + "/annotations/export")
                .param("format", "xlsx")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ANNOTATION_EXPORT_DISABLED"));
  }

  @Test
  @DisplayName("branding uploads are refused and every slot serves the bundled logo")
  void brandingIsBundledOnly() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/admin/branding/logo-light")
                .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3}))
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CUSTOM_BRANDING_DISABLED"));

    // And what is served says so too: the badge in the admin UI has to agree
    // with the logo on the page.
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(jsonPath("$.branding.logoLight.source").value("DEFAULT"));
  }

  @Test
  @DisplayName("a job cannot be started by hand, and the screen is told why")
  void schedulerManualRunIsRefused() throws Exception {
    mockMvc
        .perform(
            // A real catalogued job on purpose: the guard runs before the id is
            // looked up, so a made-up id would answer 403 too and prove nothing.
            post("/api/v1/admin/scheduler/emailVerificationTokenSweep/run")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCHEDULER_MANUAL_RUN_DISABLED"));

    // The list says so as well, so the page can drop the button instead of
    // offering one that always fails. The jobs themselves are still listed:
    // what is withheld is the manual trigger, not the schedule.
    mockMvc
        .perform(
            get("/api/v1/admin/scheduler").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.manualRunEnabled").value(false))
        .andExpect(jsonPath("$.jobSettingsEditable").value(false))
        .andExpect(jsonPath("$.items").isNotEmpty());
  }

  @Test
  @DisplayName("neither the enabled switch nor dry-run can be changed")
  void schedulerJobSettingsAreFixed() throws Exception {
    // Both in one test because they are one switch: dry-run is a soft off for
    // the jobs that delete things, so a deployment that fixes one fixes both.
    for (String body : new String[] {"{\"enabled\":false}", "{\"dryRun\":true}"}) {
      mockMvc
          .perform(
              patch("/api/v1/admin/scheduler/storageOrphanReaper")
                  .header("Authorization", "Bearer " + token(ADMIN_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("SCHEDULER_JOB_SETTINGS_DISABLED"));
    }
  }

  @Test
  @DisplayName("the mail server cannot be pointed elsewhere, but other settings still save")
  void smtpSettingsAreTheOperators() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"smtp.host\":\"mail.attacker.example\"}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SMTP_CONFIGURATION_DISABLED"));

    // The guard is selective: this endpoint carries every application setting,
    // and the rest of them are nobody's business but the administrator's.
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"general.default_language\":\"en\"}}"))
        .andExpect(status().isOk());

    // Sending from the operator's server belongs to the same screen.
    mockMvc
        .perform(
            post("/api/v1/admin/email/test")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipient\":\"someone@example.com\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SMTP_CONFIGURATION_DISABLED"));
  }

  @Test
  @DisplayName("templates cannot be edited or reset, but stay readable")
  void mailTemplatesAreReadOnly() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/email/templates/auth.password_reset")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"locale\":\"en\",\"subject\":\"Reset\","
                        + "\"bodyPlain\":\"{{link}}\",\"bodyHtml\":\"<p>{{link}}</p>\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_TEMPLATES_DISABLED"));

    mockMvc
        .perform(
            delete("/api/v1/admin/email/templates/auth.password_reset")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_TEMPLATES_DISABLED"));

    // Reading stays: a plan that excludes editing has no reason to hide the
    // wording of the mail the deployment already sends.
    mockMvc
        .perform(
            get("/api/v1/admin/email/templates")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("tracking cannot be configured here, is not shown — and still works")
  void trackingIsTheOperatorsButStillRuns() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"tracking.host\":\"https://elsewhere.example\"}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USAGE_TRACKING_DISABLED"));

    // Not merely locked: the section is gone from the screen, because an
    // endpoint an administrator cannot change tells them nothing they can act on.
    mockMvc
        .perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings[?(@.key =~ /tracking\\..*/)]").isEmpty());

    // The promise that makes this a *configuration* switch and not an off
    // switch: a configuration seeded straight into the database keeps working.
    seedSetting(ApplicationSettingKey.TRACKING_ENABLED, "true");
    seedSetting(ApplicationSettingKey.TRACKING_PROVIDER, "umami");
    seedSetting(ApplicationSettingKey.TRACKING_HOST, "https://analytics.example");
    seedSetting(ApplicationSettingKey.TRACKING_SITE_ID, "site-1");
    settings.reload();

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tracking.provider").value("umami"));
  }

  @Test
  @DisplayName("upload limits stay visible and become read-only")
  void uploadLimitsAreVisibleButFixed() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"upload.document_max_file_size_mb\":\"4096\"}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("UPLOAD_CONSTRAINTS_DISABLED"));

    // Shown, unlike tracking: an administrator who cannot raise the ceiling
    // still needs to know where it is.
    mockMvc
        .perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.settings[?(@.key == 'upload.document_max_file_size_mb')].editable")
                .value(false))
        // And an ungoverned setting is still reported as editable, so the form
        // is not read-only wholesale.
        .andExpect(
            jsonPath("$.settings[?(@.key == 'general.default_language')].editable").value(true));
  }

  @Test
  @DisplayName("self-registration is off whatever the setting says, and cannot be turned back on")
  void selfRegistrationIsWithheld() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/settings")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"auth.self_registration_enabled\":\"true\"}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SELF_REGISTRATION_DISABLED"));

    // Even with the stored setting saying yes, the capability decides.
    seedSetting(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED, "true");
    settings.reload();

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(jsonPath("$.auth.selfRegistrationEnabled").value(false));
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"newcomer\",\"email\":\"newcomer@example.com\","
                        + "\"password\":\"Sufficiently-long-passphrase-1\","
                        + "\"displayName\":\"Newcomer\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the configuration screen is refused, and the quotas it would show still apply")
  void deploymentConfigurationIsHiddenButEnforced() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/configuration").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DEPLOYMENT_CONFIGURATION_DISABLED"));

    // The quota card lives on that screen, so its endpoint goes with it.
    mockMvc
        .perform(get("/api/v1/admin/limits").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DEPLOYMENT_CONFIGURATION_DISABLED"));

    // The point of putting the guard in the controller rather than in
    // InstanceLimitService: what is withheld is the view, not the enforcement.
    // An administrator who cannot see the ceiling still stands under it.
    mockMvc
        .perform(
            post("/api/v1/admin/users")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"overflow\",\"email\":\"overflow@example.com\","
                        + "\"password\":\"Sufficiently-long-passphrase-1\","
                        + "\"displayName\":\"Overflow\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_LIMIT_EXCEEDED"));
  }

  /** Writes a setting the way an operator would: straight into the table. */
  private void seedSetting(ApplicationSettingKey key, String value) {
    applicationSettings.save(
        applicationSettings
            .findById(key.getKey())
            .map(
                row -> {
                  row.setSettingValue(value);
                  return row;
                })
            .orElseGet(() -> new ApplicationSetting(key.getKey(), value, key.getType())));
  }

  /** A review the export can be asked for; its content does not matter here. */
  private UUID seedReview() {
    Document document = new Document(MEMBER_ID, "Feature switch IT");
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    UUID documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    return documentId;
  }
}
