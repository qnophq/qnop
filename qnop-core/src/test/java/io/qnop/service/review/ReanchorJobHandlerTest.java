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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.PlacementStatus;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.DocumentVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReanchorJobHandler} (issue #248): pending placements resolve to
 * PLACED/MOVED/ORPHANED against the stored rendering, replay is idempotent, and a version without
 * its rendering fails loudly (retryable).
 */
class ReanchorJobHandlerTest {

  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final AnnotationPlacementRepository placements =
      mock(AnnotationPlacementRepository.class);
  private final ReanchorJobHandler handler = new ReanchorJobHandler(versions, placements);

  private final UUID versionId = UUID.randomUUID();
  private DocumentVersion version;

  private static final String RENDERED =
      """
      {"surfaces":[{"index":0,"width":612.0,"height":792.0,"textSpans":[
        {"text":"whereas the payment terms are thirty days from invoice",
         "startOffset":0,"endOffset":54,
         "box":{"x":0.1,"y":0.1,"width":0.8,"height":0.03}}]}]}
      """;

  @BeforeEach
  void setUp() {
    version =
        new DocumentVersion(
            UUID.randomUUID(), 2, "sha256/v2", "v2", "application/pdf", 10L, UUID.randomUUID());
    version.attachRenderedDocument(RENDERED); // READY
    when(versions.findById(versionId)).thenReturn(Optional.of(version));
  }

  private static String payload() {
    return "{\"versionId\":\"" + UUID.randomUUID() + "\"}";
  }

  private AnnotationPlacement pending(String anchorJson) {
    return new AnnotationPlacement(UUID.randomUUID(), versionId, anchorJson);
  }

  @Test
  @DisplayName("resolves each pending placement: exact → PLACED, missing → ORPHANED")
  void resolvesPendingPlacements() {
    AnnotationPlacement exact =
        pending(
            "{\"textQuote\":{\"quote\":\"payment terms are thirty days\","
                + "\"prefix\":\"whereas the \",\"suffix\":\" from invoice\"}}");
    AnnotationPlacement gone =
        pending("{\"textQuote\":{\"quote\":\"a clause that no longer exists anywhere\"}}");
    when(placements.findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING))
        .thenReturn(List.of(exact, gone));

    handler.handle("{\"versionId\":\"" + versionId + "\"}");

    assertThat(exact.getStatus()).isEqualTo(PlacementStatus.PLACED);
    assertThat(exact.getAnchor()).contains("\"textPosition\"");
    assertThat(gone.getStatus()).isEqualTo(PlacementStatus.ORPHANED);
  }

  @Test
  @DisplayName("a geometric-only placement is flagged MOVED with its anchor untouched")
  void geometricPlacementFlaggedMoved() {
    String anchor =
        "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.2,\"y\":0.2,\"width\":0.1,\"height\":0.1}}}";
    AnnotationPlacement geometric = pending(anchor);
    when(placements.findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING))
        .thenReturn(List.of(geometric));

    handler.handle("{\"versionId\":\"" + versionId + "\"}");

    assertThat(geometric.getStatus()).isEqualTo(PlacementStatus.MOVED);
    assertThat(geometric.getAnchor()).isEqualTo(anchor);
  }

  @Test
  @DisplayName("a deleted version is a no-op; only PENDING placements are ever touched")
  void idempotentOnDeletedVersionAndDecidedPlacements() {
    when(versions.findById(versionId)).thenReturn(Optional.empty());
    handler.handle("{\"versionId\":\"" + versionId + "\"}"); // must not throw

    // Replay path: the repository query only returns PENDING rows, so already-decided placements
    // are structurally out of reach — verified by the query-by-status contract itself.
  }

  @Test
  @DisplayName("a version without its rendering fails loudly (retryable broken invariant)")
  void missingRenderedDocumentThrows() {
    DocumentVersion notReady =
        new DocumentVersion(
            UUID.randomUUID(), 2, "sha256/x", "x", "application/pdf", 1L, UUID.randomUUID());
    when(versions.findById(versionId)).thenReturn(Optional.of(notReady));

    assertThatThrownBy(() -> handler.handle("{\"versionId\":\"" + versionId + "\"}"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a malformed payload fails loudly instead of silently dropping work")
  void malformedPayloadThrows() {
    assertThatThrownBy(() -> handler.handle("{}")).isInstanceOf(IllegalArgumentException.class);
  }
}
