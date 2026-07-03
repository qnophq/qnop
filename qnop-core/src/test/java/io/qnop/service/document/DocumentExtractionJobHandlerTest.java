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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobService;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.extract.DocumentExtractor;
import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.extract.NormalizedBox;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.spi.extract.TextSpan;
import io.qnop.spi.storage.StorageContent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for {@link DocumentExtractionJobHandler}'s failure policy and idempotency (#245). */
class DocumentExtractionJobHandlerTest {

  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final StorageService storage = mock(StorageService.class);
  private final AnnotationPlacementRepository placements =
      mock(AnnotationPlacementRepository.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<JobService> jobs = mock(ObjectProvider.class);

  private final UUID versionId = UUID.randomUUID();
  private DocumentVersion version;

  @BeforeEach
  void setUp() {
    version =
        new DocumentVersion(
            UUID.randomUUID(), 1, "sha256/abc", "abc", "application/pdf", 10L, UUID.randomUUID());
  }

  private DocumentExtractionJobHandler handler(DocumentExtractor... extractors) {
    // The write phase lives in DocumentExtractionWriter (issue #314); in this unit test it runs
    // directly (no Spring proxy), over the same mocked repositories.
    DocumentExtractionWriter writer = new DocumentExtractionWriter(versions, placements, jobs);
    return new DocumentExtractionJobHandler(versions, storage, List.of(extractors), writer);
  }

  private static String payload(UUID versionId) {
    return DocumentIngestService.extractionPayload(versionId);
  }

  private void givenStoredContent() {
    when(storage.get("sha256/abc"))
        .thenReturn(
            Optional.of(
                new StorageContent(
                    new ByteArrayInputStream(new byte[] {1}), 1L, "application/pdf")));
  }

  @Test
  @DisplayName("happy path: attaches the rendered json and flips the status to READY")
  void attachesRenderedDocumentOnSuccess() {
    when(versions.findById(versionId)).thenReturn(Optional.of(version));
    givenStoredContent();
    RenderedDocument rendered =
        new RenderedDocument(
            List.of(
                new Surface(
                    0,
                    612,
                    792,
                    List.of(new TextSpan("hi", 0, 2, new NormalizedBox(0.1, 0.1, 0.2, 0.05))))));
    handler(fixedExtractor(rendered)).handle(payload(versionId));

    verify(versions).save(version);
    assertThat(version.getExtractionStatus()).isEqualTo(ExtractionStatus.READY);
    assertThat(version.getRenderedDocument()).contains("\"surfaces\"").contains("\"hi\"");
  }

  @Test
  @DisplayName("unprocessable content marks the version FAILED and completes (no retry)")
  void marksFailedOnExtractionException() {
    when(versions.findById(versionId)).thenReturn(Optional.of(version));
    givenStoredContent();

    handler(throwingExtractor()).handle(payload(versionId));

    verify(versions).save(version);
    assertThat(version.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
    assertThat(version.getRenderedDocument()).isNull();
  }

  @Test
  @DisplayName("a content type without extractor marks the version FAILED (permanent)")
  void marksFailedWithoutExtractor() {
    when(versions.findById(versionId)).thenReturn(Optional.of(version));

    handler(/* no extractors */ ).handle(payload(versionId));

    assertThat(version.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
  }

  @Test
  @DisplayName("a missing stored object propagates (retryable) without touching the version")
  void missingObjectStaysRetryable() {
    when(versions.findById(versionId)).thenReturn(Optional.of(version));
    when(storage.get("sha256/abc")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> handler(fixedExtractor(null)).handle(payload(versionId)))
        .isInstanceOf(IllegalStateException.class);
    verify(versions, never()).save(version);
    assertThat(version.getExtractionStatus()).isEqualTo(ExtractionStatus.PENDING);
  }

  @Test
  @DisplayName("idempotent: a deleted version or an already-READY version is a no-op")
  void idempotentOnMissingOrReadyVersion() {
    when(versions.findById(versionId)).thenReturn(Optional.empty());
    handler(fixedExtractor(null)).handle(payload(versionId));
    verify(versions, never()).save(version);

    version.attachRenderedDocument("{}");
    when(versions.findById(versionId)).thenReturn(Optional.of(version));
    handler(fixedExtractor(null)).handle(payload(versionId));
    verify(versions, never()).save(version);
  }

  private static DocumentExtractor fixedExtractor(RenderedDocument result) {
    return new DocumentExtractor() {
      @Override
      public boolean supports(String contentType) {
        return "application/pdf".equals(contentType);
      }

      @Override
      public RenderedDocument extract(InputStream content) {
        return result;
      }
    };
  }

  private static DocumentExtractor throwingExtractor() {
    return new DocumentExtractor() {
      @Override
      public boolean supports(String contentType) {
        return true;
      }

      @Override
      public RenderedDocument extract(InputStream content) throws ExtractionException {
        throw new ExtractionException("corrupt");
      }
    };
  }
}
