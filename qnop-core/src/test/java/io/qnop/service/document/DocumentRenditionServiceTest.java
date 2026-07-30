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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.DocumentVersion;
import io.qnop.service.convert.OfficeConversionException;
import io.qnop.service.convert.OfficeConverter;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.storage.StorageContent;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Converting a Word upload into the PDF the viewer renders (issue #343, ADR-0010). */
class DocumentRenditionServiceTest {

  private final OfficeConverter converter = mock(OfficeConverter.class);
  private final StorageService storage = mock(StorageService.class);
  private final DocumentExtractionWriter writer = mock(DocumentExtractionWriter.class);

  private final DocumentRenditionService service =
      new DocumentRenditionService(converter, storage, writer);

  private static DocumentVersion version(String contentType) {
    return new DocumentVersion(
        UUID.randomUUID(), 1, "sha256/aa/upload", "hash", contentType, 42L, UUID.randomUUID());
  }

  private void givenUploadInStorage() {
    when(storage.get("sha256/aa/upload"))
        .thenReturn(
            Optional.of(
                new StorageContent(
                    new ByteArrayInputStream("PK".getBytes()), 2L, DocumentTypeSniffer.DOCX)));
  }

  @Test
  @DisplayName("a PDF upload is its own rendition and never reaches the converter")
  void pdfNeedsNoConversion() throws Exception {
    DocumentVersion pdf = version(DocumentTypeSniffer.PDF);

    assertThat(service.renderableKey(pdf)).isEqualTo("sha256/aa/upload");
    verifyNoInteractions(converter, storage, writer);
  }

  @Test
  @DisplayName("a Word upload is converted once, stored, and only then committed")
  void convertsWordOnce() throws Exception {
    DocumentVersion docx = version(DocumentTypeSniffer.DOCX);
    givenUploadInStorage();
    when(converter.toPdf(any(), eq("docx"))).thenReturn("%PDF-1.7".getBytes());
    when(storage.stage(any(), eq(DocumentTypeSniffer.PDF)))
        .thenReturn(new StagedObject("sha256/bb/pdf", "pdfhash", 8L));
    when(writer.attachRendition(docx.getId(), "sha256/bb/pdf")).thenReturn("sha256/bb/pdf");

    assertThat(service.renderableKey(docx)).isEqualTo("sha256/bb/pdf");

    // Upload-then-commit (ADR-0036): the row that references the object is written
    // before the object is committed, so a crash between them leaves an orphan the
    // reaper reclaims rather than a version pointing at nothing.
    verify(writer).attachRendition(docx.getId(), "sha256/bb/pdf");
    verify(storage).commit("sha256/bb/pdf");
  }

  @Test
  @DisplayName("a version that already has a rendition is not converted again")
  void reusesTheStoredRendition() throws Exception {
    // The load-bearing case for ADR-0033's idempotency: converting is not
    // byte-deterministic, so a replay that converted again would mint a second key,
    // orphan the first, and move the pages under annotations already placed on them.
    DocumentVersion docx = version(DocumentTypeSniffer.DOCX);
    docx.setRenditionStorageKey("sha256/bb/already-there");

    assertThat(service.renderableKey(docx)).isEqualTo("sha256/bb/already-there");
    verifyNoInteractions(converter);
    verify(storage, never()).stage(any(), anyString());
  }

  @Test
  @DisplayName("a document the converter cannot read fails the version instead of retrying")
  void unreadableDocumentIsPermanent() {
    DocumentVersion docx = version(DocumentTypeSniffer.DOCX);
    givenUploadInStorage();
    when(converter.toPdf(any(), anyString()))
        .thenThrow(OfficeConversionException.unreadableDocument("produced no PDF"));

    // ExtractionException is the pipeline's word for "permanent": the handler marks
    // the version FAILED and the job completes.
    assertThatThrownBy(() -> service.renderableKey(docx))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("could not be converted");
  }

  @Test
  @DisplayName("a missing or slow converter stays retryable rather than failing the version")
  void environmentFailureIsRetryable() {
    DocumentVersion docx = version(DocumentTypeSniffer.DOCX);
    givenUploadInStorage();
    when(converter.toPdf(any(), anyString()))
        .thenThrow(new OfficeConversionException("no office converter is installed"));

    // Nothing is wrong with the document, and an operator installing the converter
    // should heal it — so this must not become a permanent FAILED.
    assertThatThrownBy(() -> service.renderableKey(docx))
        .isInstanceOf(OfficeConversionException.class);
    verify(storage, never()).commit(anyString());
  }

  @Test
  @DisplayName("a rendition that lost a race is left uncommitted for the reaper")
  void discardsTheLoserOfARace() throws Exception {
    DocumentVersion docx = version(DocumentTypeSniffer.DOCX);
    givenUploadInStorage();
    when(converter.toPdf(any(), anyString())).thenReturn("%PDF-1.7".getBytes());
    when(storage.stage(any(), eq(DocumentTypeSniffer.PDF)))
        .thenReturn(new StagedObject("sha256/cc/mine", "h", 8L));
    // Another run of the same job got there first.
    when(writer.attachRendition(docx.getId(), "sha256/cc/mine")).thenReturn("sha256/bb/theirs");

    assertThat(service.renderableKey(docx)).isEqualTo("sha256/bb/theirs");

    // Committing ours would make it a permanent orphan: the reaper only reclaims
    // what was never committed.
    verify(storage, never()).commit(anyString());
  }

  @Test
  @DisplayName("Word is offered only where a converter is installed")
  void supportsFollowsTheConverter() {
    when(converter.isAvailable()).thenReturn(false);
    assertThat(service.supports(DocumentTypeSniffer.PDF)).isTrue();
    assertThat(service.supports(DocumentTypeSniffer.DOCX)).isFalse();

    when(converter.isAvailable()).thenReturn(true);
    assertThat(service.supports(DocumentTypeSniffer.DOCX)).isTrue();

    assertThat(service.supports("image/png")).isFalse();
  }
}
