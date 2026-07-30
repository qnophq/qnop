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

import io.qnop.entity.DocumentVersion;
import io.qnop.service.convert.OfficeConversionException;
import io.qnop.service.convert.OfficeConverter;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.extract.ExtractionException;
import io.qnop.spi.storage.StorageContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Produces the PDF a version is viewed through (issue #343, ADR-0010).
 *
 * <p>DOCX is converted to PDF on ingest and then flows through exactly the same pipeline as a
 * native PDF: PDFBox extracts the spans, the client renders with PDF.js, and anchoring, diff and
 * workflow never learn that a second format exists.
 *
 * <p>ADR-0010 put this behind the {@code DocumentExtractor} SPI — "convert, then delegate to the
 * PDF extractor". It cannot live there: an extractor returns only {@link
 * io.qnop.spi.extract.RenderedDocument}, which is geometry and text and never pixels, so a DOCX
 * extractor would convert and then throw the converted PDF away, leaving the viewer with nothing to
 * render. The seam is therefore one step earlier, here in the pipeline, and the published SPI is
 * untouched.
 */
@Service
public class DocumentRenditionService {

  private static final Logger log = LoggerFactory.getLogger(DocumentRenditionService.class);

  private final OfficeConverter converter;
  private final StorageService storage;
  private final DocumentExtractionWriter writer;

  DocumentRenditionService(
      OfficeConverter converter, StorageService storage, DocumentExtractionWriter writer) {
    this.converter = converter;
    this.storage = storage;
    this.writer = writer;
  }

  /** Whether this server can ingest a document of the given (sniffed) type. */
  public boolean supports(String contentType) {
    if (DocumentTypeSniffer.PDF.equals(contentType)) {
      return true;
    }
    // Word needs a converter, and a server without one must say so at upload time
    // rather than accept a document it can never render (issue #343).
    return DocumentTypeSniffer.DOCX.equals(contentType) && converter.isAvailable();
  }

  /** Whether a version of this type is viewed through a converted PDF rather than its upload. */
  public boolean needsRendition(String contentType) {
    return !DocumentTypeSniffer.PDF.equals(contentType);
  }

  /**
   * The storage key to extract and render this version from, converting first when the upload is
   * not already a PDF.
   *
   * <p>The conversion is stored, not recomputed per view: it costs a subprocess, and a review's
   * pages must not shift under the annotations placed on them. It also runs at most once per
   * version — a replay finds the key already written and reuses it.
   *
   * @throws ExtractionException when the converter read the document and could not make sense of it
   *     — permanent, so the caller fails the version rather than retrying forever
   */
  public String renderableKey(DocumentVersion version) throws ExtractionException {
    if (!needsRendition(version.getContentType())) {
      return version.getStorageKey();
    }
    if (version.getRenditionStorageKey() != null) {
      return version.getRenditionStorageKey();
    }

    byte[] source = read(version);
    byte[] pdf;
    try {
      pdf = converter.toPdf(source, "docx");
    } catch (OfficeConversionException e) {
      if (e.isPermanent()) {
        throw new ExtractionException("the document could not be converted: " + e.getMessage(), e);
      }
      // No converter installed, or it timed out: the environment, not the file.
      // Propagating keeps the job retryable under the queue's backoff.
      throw e;
    }

    // Upload-then-commit (ADR-0036), in the order the ingest path uses: the object
    // is staged, the row that references it is written, and only then is the object
    // committed. A crash in between leaves an orphan the reaper reclaims — never a
    // version pointing at something that was never stored.
    StagedObject staged = storage.stage(new ByteArrayInputStream(pdf), DocumentTypeSniffer.PDF);
    String inForce = writer.attachRendition(version.getId(), staged.key());
    if (!staged.key().equals(inForce)) {
      // Either the version was deleted while this converted, or two runs of the same
      // job raced and the other one's key is the stored one. Ours is referenced by
      // nothing, so it is left uncommitted for the reaper (ADR-0036) rather than
      // committed into permanent orphanhood.
      log.info(
          "Discarding an unreferenced rendition of version {}; stored key is {}.",
          version.getId(),
          inForce);
      return inForce == null ? staged.key() : inForce;
    }
    storage.commit(staged.key());
    return inForce;
  }

  private byte[] read(DocumentVersion version) {
    try (StorageContent content =
        storage
            .get(version.getStorageKey())
            .orElseThrow(
                () ->
                    new IllegalStateException( // retryable: the object should exist post-commit
                        "stored object missing for version " + version.getId()))) {
      return content.stream().readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("could not read the upload of version " + version.getId(), e);
    }
  }
}
