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
package io.qnop.audit;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The organisation-wide audit trail over the wire (issue #466, ADR-0042): the AUDITOR/ADMIN role
 * gate (a MEMBER is forbidden), the filters (event type, actor, document, created-at range) and
 * bounded paging, real actor/document name resolution — including the caller's OWN actions, which
 * this view keeps (unlike the dashboard feed) — and the system actor rendered as "System".
 */
class AuditApiIT extends SeededIntegrationTest {

  private static final String AUDIT = "/api/v1/audit/events";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AuditEventRepository auditEvents;

  private UUID seedDocumentWithVersion(String title) {
    Document document = documents.save(new Document(MEMBER_ID, title));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    versions.save(
        new DocumentVersion(
            document.getId(),
            1,
            "sha256/aa/deadbeef",
            "deadbeef",
            "application/pdf",
            1234L,
            MEMBER_ID));
    return document.getId();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private String createAnnotation(UUID documentId, UUID actor, String comment) throws Exception {
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"versionNumber\":1,\"comment\":\"" + comment + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private void resolve(String annotationId, UUID actor) throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/resolve"), actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
  }

  /** MEMBER creates + resolves, AUDITOR creates — three events across one document. */
  private UUID seedTrail() throws Exception {
    UUID documentId = seedDocumentWithVersion("Master services agreement");
    String mine = createAnnotation(documentId, MEMBER_ID, "please clarify");
    createAnnotation(documentId, AUDITOR_ID, "a second concern");
    resolve(mine, MEMBER_ID);
    return documentId;
  }

  @Test
  @DisplayName("a MEMBER is forbidden — the first real AUDITOR gate")
  void memberForbidden() throws Exception {
    mockMvc.perform(as(get(AUDIT), MEMBER_ID)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("an unauthenticated caller is unauthorized")
  void requiresAuth() throws Exception {
    mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("an ADMIN reads the org-wide trail even without participating")
  void adminSeesTrail() throws Exception {
    seedTrail();
    mockMvc
        .perform(as(get(AUDIT), ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items", hasSize(3)));
  }

  @Test
  @DisplayName("an AUDITOR sees the whole trail newest-first, with resolved names and OWN actions")
  void auditorSeesOrgWideTrail() throws Exception {
    seedTrail();
    mockMvc
        .perform(as(get(AUDIT), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.page").value(0))
        // Newest first: MEMBER's resolve leads, with the document and actor resolved to names.
        .andExpect(jsonPath("$.items[0].eventType").value("annotation.resolved"))
        .andExpect(jsonPath("$.items[0].documentTitle").value("Master services agreement"))
        .andExpect(jsonPath("$.items[0].actorId").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$.items[0].actorDisplayName").value("Mia Member"))
        .andExpect(jsonPath("$.items[0].actorSlug").value("mia-member"))
        // Unlike the dashboard feed, the caller's OWN action is present in the audit trail.
        .andExpect(jsonPath("$.items[*].actorId", hasItem(AUDITOR_ID.toString())));
  }

  @Test
  @DisplayName("filters by event type")
  void filtersByEventType() throws Exception {
    seedTrail();
    mockMvc
        .perform(as(get(AUDIT).param("eventType", "annotation.created"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[*].eventType", everyItem(is("annotation.created"))));
  }

  @Test
  @DisplayName("filters by actor")
  void filtersByActor() throws Exception {
    seedTrail();
    mockMvc
        .perform(as(get(AUDIT).param("actorId", AUDITOR_ID.toString()), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[*].actorId", everyItem(is(AUDITOR_ID.toString()))));
  }

  @Test
  @DisplayName("filters by document")
  void filtersByDocument() throws Exception {
    UUID first = seedTrail();
    UUID second = seedDocumentWithVersion("Statement of work");
    createAnnotation(second, MEMBER_ID, "scope note");

    mockMvc
        .perform(as(get(AUDIT).param("documentId", second.toString()), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[*].documentId", everyItem(is(second.toString()))));
    mockMvc
        .perform(as(get(AUDIT).param("documentId", first.toString()), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));
  }

  @Test
  @DisplayName("filters by created-at range on both bounds")
  void filtersByCreatedAtRange() throws Exception {
    seedTrail();
    // A `to` far in the past excludes everything; an open-ended `from` in the past includes all.
    mockMvc
        .perform(as(get(AUDIT).param("to", "2000-01-01T00:00:00Z"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    mockMvc
        .perform(as(get(AUDIT).param("from", "2000-01-01T00:00:00Z"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", greaterThanOrEqualTo(3)));
  }

  @Test
  @DisplayName("a system-generated event (no actor) renders as System")
  void systemActorRendersAsSystem() throws Exception {
    UUID documentId = seedDocumentWithVersion("Ingest report");
    auditEvents.save(
        new AuditEvent(documentId, "document.extraction.failed", null, "{\"reason\":\"BAD_PDF\"}"));

    mockMvc
        .perform(as(get(AUDIT).param("eventType", "document.extraction.failed"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].actorId").doesNotExist())
        .andExpect(jsonPath("$.items[0].actorDisplayName").value("System"))
        // PostgreSQL re-serialises jsonb, so the detail comes back with a space after the colon.
        .andExpect(jsonPath("$.items[0].detail").value("{\"reason\": \"BAD_PDF\"}"));
  }

  @Test
  @DisplayName("a page size beyond the contract maximum is rejected (bean validation)")
  void boundedPageSize() throws Exception {
    // The OpenAPI `maximum: 100` becomes @Max(100) on the endpoint, so an over-max size is a 400
    // at the boundary — the same bound the admin list enforces. The service also clamps defensively
    // for direct (non-HTTP) callers (see AuditLogServiceTest).
    mockMvc
        .perform(as(get(AUDIT).param("size", "500"), AUDITOR_ID))
        .andExpect(status().isBadRequest());
  }
}
