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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
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
      "qnop.features.custom-branding=false"
    })
class FeatureDisabledIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
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
