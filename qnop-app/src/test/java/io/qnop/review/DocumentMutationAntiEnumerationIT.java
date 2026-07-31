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
package io.qnop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Anti-enumeration across every mutating single-document endpoint (issue #661).
 *
 * <p>The read paths answer a non-participant 404 so a review id cannot be probed for existence —
 * "non-participants must not learn that the document exists" ({@code DocumentAccessService}). The
 * fix for #661 aligned archive/unarchive with that; this pins the whole family so a future endpoint
 * cannot quietly reintroduce the leak.
 *
 * <p>Two halves, and both matter:
 *
 * <ul>
 *   <li><b>The guard</b> fires each endpoint as a non-participant against a real foreign review and
 *       against an unknown id, and asserts the two are indistinguishable — the same 404. A 403 (or
 *       a success) on the foreign one but a 404 on the unknown one is exactly the leak.
 *   <li><b>The completeness check</b> discovers the mutating {@code
 *       /documents/&#123;documentId&#125;} endpoints from the live handler mapping and asserts the
 *       guard covers every one. A new endpoint that is not listed fails the build rather than
 *       slipping through unguarded.
 * </ul>
 *
 * <p>Each request is crafted only well enough to pass argument resolution and reach the service's
 * authorization — no business-valid body is needed. That is self-checking: a body too thin to reach
 * authorization would 400 rather than 404, and the guard would fail loudly, not pass silently.
 */
class DocumentMutationAntiEnumerationIT extends SeededIntegrationTest {

  /** A real, enabled user who is not the owner and not a participant of the review under test. */
  private static final UUID NON_PARTICIPANT = MEMBER2_ID;

  private static final String DOCUMENTS = "/api/v1/documents/";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;

  private record Endpoint(
      String method,
      String template,
      Function<UUID, AbstractMockHttpServletRequestBuilder<?>> request) {
    String signature() {
      return method + " " + template;
    }
  }

  private static MockMultipartFile filePart() {
    return new MockMultipartFile(
        "file", "probe.pdf", "application/pdf", "%PDF-1.4\n%%EOF\n".getBytes());
  }

  /**
   * One entry per mutating single-document endpoint. Kept in lockstep with the live mapping by
   * {@link #everyMutatingDocumentEndpointIsCovered()} — add the endpoint here when that fails.
   */
  private List<Endpoint> guardedEndpoints() {
    return List.of(
        new Endpoint("DELETE", "/api/v1/documents/{documentId}", id -> delete(DOCUMENTS + id)),
        new Endpoint(
            "DELETE",
            "/api/v1/documents/{documentId}/participants/{participantId}",
            id -> delete(DOCUMENTS + id + "/participants/" + UUID.randomUUID())),
        new Endpoint(
            "PATCH",
            "/api/v1/documents/{documentId}",
            id -> patch(DOCUMENTS + id).contentType(MediaType.APPLICATION_JSON).content("{}")),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/annotations",
            id ->
                post(DOCUMENTS + id + "/annotations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"versionNumber\":1,\"comment\":\"probe\"}")),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/archive",
            id -> post(DOCUMENTS + id + "/archive")),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/attachments",
            id -> multipart(DOCUMENTS + id + "/attachments").file(filePart())),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/participants",
            id ->
                post(DOCUMENTS + id + "/participants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + UUID.randomUUID() + "\"}")),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/unarchive",
            id -> post(DOCUMENTS + id + "/unarchive")),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/versions",
            id -> multipart(DOCUMENTS + id + "/versions").file(filePart())),
        new Endpoint(
            "POST", "/api/v1/documents/{documentId}/visit", id -> post(DOCUMENTS + id + "/visit")),
        new Endpoint(
            "POST",
            "/api/v1/documents/{documentId}/workflow",
            id ->
                post(DOCUMENTS + id + "/workflow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetState\":\"FINALIZED\"}")));
  }

  @Test
  @DisplayName("every mutating document endpoint answers a non-participant 404, unknown or not")
  void everyMutatingDocumentEndpointHidesExistenceFromNonParticipants() throws Exception {
    UUID foreign = foreignReview();
    UUID unknown = UUID.randomUUID();

    for (Endpoint endpoint : guardedEndpoints()) {
      int onForeign = statusAsNonParticipant(endpoint.request().apply(foreign));
      int onUnknown = statusAsNonParticipant(endpoint.request().apply(unknown));

      assertThat(onForeign)
          .as(
              "%s must answer a non-participant the same for a real-but-invisible review as for an"
                  + " unknown id — a differing status leaks that the review exists",
              endpoint.signature())
          .isEqualTo(404)
          .isEqualTo(onUnknown);
    }
  }

  @Test
  @DisplayName("the guard covers every mutating /documents/{documentId} endpoint that exists")
  void everyMutatingDocumentEndpointIsCovered() {
    Set<String> live = discoverMutatingDocumentEndpoints();
    Set<String> guarded =
        guardedEndpoints().stream()
            .map(Endpoint::signature)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(live)
        .as(
            "a mutating /documents/{documentId} endpoint exists that the anti-enumeration guard does"
                + " not cover (issue #661). Add it to guardedEndpoints() with a request that reaches"
                + " the service, so it cannot leak a review's existence unnoticed.")
        .isEqualTo(guarded);
  }

  private int statusAsNonParticipant(AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return mockMvc
        .perform(request.header("Authorization", "Bearer " + token(NON_PARTICIPANT)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** A MEMBER-owned review with one version and no participants — invisible to NON_PARTICIPANT. */
  private UUID foreignReview() {
    Document document = new Document(MEMBER_ID, "Anti-enumeration foreign review");
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    UUID documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "anti-enum-key", "hash", "application/pdf", 8L, MEMBER_ID));
    return documentId;
  }

  /** Mutating verbs on a path keyed directly on {@code {documentId}}, from the live mapping. */
  private Set<String> discoverMutatingDocumentEndpoints() {
    Set<String> found = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      var patterns = info.getPathPatternsCondition();
      if (patterns == null) {
        continue;
      }
      for (var pattern : patterns.getPatterns()) {
        String path = pattern.getPatternString();
        if (!path.contains("/documents/{documentId}")) {
          continue;
        }
        for (var method : info.getMethodsCondition().getMethods()) {
          if (!method.name().equals("GET")) {
            found.add(method.name() + " " + path);
          }
        }
      }
    }
    return found;
  }
}
