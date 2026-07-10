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
package io.qnop.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.api.v1.model.RenderedDocumentResponse;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.storage.StorageContent;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DocumentAccessService} (issue #349): the read-side authorization contract.
 * A document is visible to its owner, any participant (direct or via team — resolved in one repo
 * query, so indistinguishable here and left to {@code DocumentServingAuthzIT}) and a global admin;
 * everyone else gets a 404, never a 403. The rendered/original readers enforce the same visibility.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAccessServiceTest {

  @Mock private DocumentRepository documents;
  @Mock private DocumentVersionRepository versions;
  @Mock private ReviewParticipantRepository participants;
  @Mock private StorageService storage;

  private DocumentAccessService service;

  private static final UUID DOC = UUID.randomUUID();
  private static final UUID OWNER = UUID.randomUUID();
  private static final UUID PARTICIPANT = UUID.randomUUID();
  private static final UUID STRANGER = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DocumentAccessService(documents, versions, participants, storage);
  }

  private Document ownedDocument() {
    return new Document(OWNER, "Contract");
  }

  private DocumentVersion version(int number) {
    return new DocumentVersion(
        DOC, number, "storage/key-" + number, "hash-" + number, "application/pdf", 42L, OWNER);
  }

  // --- getDocument visibility ------------------------------------------------

  @Test
  @DisplayName("the owner sees the document, with the latest version number resolved")
  void ownerSeesDocument() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findTopByDocumentIdOrderByVersionNumberDesc(DOC))
        .thenReturn(Optional.of(version(3)));

    DocumentAccessService.DocumentView view = service.getDocument(DOC, OWNER, false);

    assertThat(view.ownerId()).isEqualTo(OWNER);
    assertThat(view.workflowState()).isEqualTo("DRAFT");
    assertThat(view.latestVersionNumber()).isEqualTo(3);
    // The owner short-circuits authorization — the participant query is never run.
    verify(participants, never()).existsAccessibleParticipant(any(), any());
  }

  @Test
  @DisplayName("a global admin sees any document without a participant lookup")
  void adminSeesDocument() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findTopByDocumentIdOrderByVersionNumberDesc(DOC)).thenReturn(Optional.empty());

    DocumentAccessService.DocumentView view = service.getDocument(DOC, STRANGER, true);

    assertThat(view.latestVersionNumber()).isZero();
    verify(participants, never()).existsAccessibleParticipant(any(), any());
  }

  @Test
  @DisplayName("a review participant sees the document")
  void participantSeesDocument() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(participants.existsAccessibleParticipant(any(), eq(PARTICIPANT))).thenReturn(true);
    when(versions.findTopByDocumentIdOrderByVersionNumberDesc(DOC)).thenReturn(Optional.empty());

    assertThat(service.getDocument(DOC, PARTICIPANT, false).ownerId()).isEqualTo(OWNER);
  }

  @Test
  @DisplayName("a non-participant gets 404, not 403 (ids stay non-enumerable)")
  void nonParticipantGets404() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    // existsAccessibleParticipant defaults to false → not accessible.

    assertThatThrownBy(() -> service.getDocument(DOC, STRANGER, false))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
  }

  @Test
  @DisplayName("an unknown document is 404")
  void unknownDocumentGets404() {
    when(documents.findById(DOC)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getDocument(DOC, OWNER, false))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
  }

  @Test
  @DisplayName("isVisible returns a boolean rather than throwing")
  void isVisibleReturnsBoolean() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));

    assertThat(service.isVisible(DOC, OWNER, false)).isTrue();
    assertThat(service.isVisible(DOC, STRANGER, false)).isFalse();
  }

  @Test
  @DisplayName("an unknown or invisible slug is 404")
  void unknownSlugGets404() {
    when(documents.findBySlugIgnoreCase("mystery")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getDocumentBySlug("mystery", OWNER, false))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
  }

  // --- listVersions ----------------------------------------------------------

  @Test
  @DisplayName("listVersions maps a visible document's versions")
  void listsVersionsForVisibleDocument() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdOrderByVersionNumberAsc(DOC)).thenReturn(List.of(version(1)));

    List<DocumentAccessService.DocumentVersionView> list = service.listVersions(DOC, OWNER, false);

    assertThat(list).hasSize(1);
    assertThat(list.get(0).versionNumber()).isEqualTo(1);
    assertThat(list.get(0).extractionStatus()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("listVersions enforces visibility (non-participant → 404)")
  void listVersionsEnforcesVisibility() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));

    assertThatThrownBy(() -> service.listVersions(DOC, STRANGER, false))
        .isInstanceOf(DocumentValidationException.class);
    verify(versions, never()).findByDocumentIdOrderByVersionNumberAsc(any());
  }

  // --- getRendered -----------------------------------------------------------

  @Test
  @DisplayName("getRendered parses a READY version's stored representation")
  void rendersReadyVersion() {
    DocumentVersion ready = version(1);
    ready.attachRenderedDocument("{\"surfaces\":[]}");
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdAndVersionNumber(DOC, 1)).thenReturn(Optional.of(ready));

    RenderedDocumentResponse response = service.getRendered(DOC, 1, OWNER, false);

    assertThat(response).isNotNull();
    assertThat(response.getSurfaces()).isEmpty();
  }

  @Test
  @DisplayName("getRendered is 409 EXTRACTION_FAILED for a failed version")
  void renderedFailedIs409() {
    DocumentVersion failed = version(1);
    failed.markExtractionFailed();
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdAndVersionNumber(DOC, 1)).thenReturn(Optional.of(failed));

    assertThatThrownBy(() -> service.getRendered(DOC, 1, OWNER, false))
        .isInstanceOfSatisfying(
            DocumentValidationException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(409);
              assertThat(e.getCode()).isEqualTo("EXTRACTION_FAILED");
            });
  }

  @Test
  @DisplayName("getRendered is 409 EXTRACTION_PENDING while extraction has not completed")
  void renderedPendingIs409() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdAndVersionNumber(DOC, 1)).thenReturn(Optional.of(version(1)));

    assertThatThrownBy(() -> service.getRendered(DOC, 1, OWNER, false))
        .isInstanceOfSatisfying(
            DocumentValidationException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(409);
              assertThat(e.getCode()).isEqualTo("EXTRACTION_PENDING");
            });
  }

  @Test
  @DisplayName("getRendered fails loudly when the stored JSON does not match the contract")
  void renderedCorruptJsonThrows() {
    DocumentVersion ready = version(1);
    ready.attachRenderedDocument("not-json");
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdAndVersionNumber(DOC, 1)).thenReturn(Optional.of(ready));

    assertThatThrownBy(() -> service.getRendered(DOC, 1, OWNER, false))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("getRendered enforces visibility before touching the version")
  void renderedEnforcesVisibility() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));

    assertThatThrownBy(() -> service.getRendered(DOC, 1, STRANGER, false))
        .isInstanceOf(DocumentValidationException.class);
    verify(versions, never()).findByDocumentIdAndVersionNumber(any(), eq(1));
  }

  // --- getOriginal -----------------------------------------------------------

  @Test
  @DisplayName("getOriginal streams the stored object with serving metadata")
  void originalStreamsStoredObject() {
    DocumentVersion ver = version(1);
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdAndVersionNumber(DOC, 1)).thenReturn(Optional.of(ver));
    when(storage.get("storage/key-1"))
        .thenReturn(
            Optional.of(
                new StorageContent(
                    new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L, "application/pdf")));

    try (DocumentAccessService.OriginalDownload download =
        service.getOriginal(DOC, 1, OWNER, false)) {
      assertThat(download.title()).isEqualTo("Contract");
      assertThat(download.versionNumber()).isEqualTo(1);
      assertThat(download.contentLength()).isEqualTo(3L);
      assertThat(download.contentType()).isEqualTo("application/pdf");
    }
  }

  @Test
  @DisplayName("getOriginal fails loudly when the stored object is missing")
  void originalMissingObjectThrows() {
    DocumentVersion ver = version(1);
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));
    when(versions.findByDocumentIdAndVersionNumber(DOC, 1)).thenReturn(Optional.of(ver));
    when(storage.get("storage/key-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getOriginal(DOC, 1, OWNER, false))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("getOriginal enforces visibility before touching storage")
  void originalEnforcesVisibility() {
    when(documents.findById(DOC)).thenReturn(Optional.of(ownedDocument()));

    assertThatThrownBy(() -> service.getOriginal(DOC, 1, STRANGER, false))
        .isInstanceOf(DocumentValidationException.class);
    verify(storage, never()).get(any());
  }
}
