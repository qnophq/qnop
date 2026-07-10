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

import io.qnop.service.document.DocumentAttachmentService;
import io.qnop.service.document.DocumentAttachmentService.AttachmentDownload;
import io.qnop.service.document.DocumentAttachmentService.AttachmentMetadata;
import io.qnop.service.document.DocumentAttachmentService.UploadedAttachment;
import io.qnop.service.document.UploadSource;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Comment image attachments (issue #446). A plain controller by design (ADR-0028): binary
 * upload/download stays outside the generated OpenAPI contract. Both directions are
 * participant-gated through the service (404 for outsiders, anti-enumeration) — review content is
 * confidential, so unlike avatars (ADR-0031) the read path is NOT public; the SPA fetches it with
 * the bearer and shows a blob URL. Attachments are immutable, so the {@code ETag} is the content
 * hash and a matching {@code If-None-Match} short-circuits to 304 without touching storage.
 */
@RestController
@RequestMapping("/documents/{documentId}/attachments") // mounted under /api/v1 by ApiPathConfig
public class DocumentAttachmentController {

  private final DocumentAttachmentService attachments;

  public DocumentAttachmentController(DocumentAttachmentService attachments) {
    this.attachments = attachments;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AttachmentUploadResponse> upload(
      @PathVariable UUID documentId, @RequestParam("file") MultipartFile file) {
    UploadedAttachment uploaded =
        attachments.store(
            documentId,
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            sourceOf(file),
            file.getOriginalFilename());
    String url = "/api/v1/documents/" + documentId + "/attachments/" + uploaded.id();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new AttachmentUploadResponse(
                uploaded.id(), url, uploaded.fileName(), uploaded.contentType()));
  }

  @GetMapping("/{attachmentId}")
  public ResponseEntity<InputStreamResource> serve(
      @PathVariable UUID documentId,
      @PathVariable UUID attachmentId,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    AttachmentMetadata metadata =
        attachments.metadata(
            documentId, attachmentId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    String etag = "\"" + metadata.contentHash() + "\"";
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(304).eTag(etag).cacheControl(cachePolicy()).build();
    }
    AttachmentDownload content = attachments.open(metadata);
    // Only sniffed raster images may render inline; every other type downloads,
    // so an uploaded HTML/SVG payload can never execute in the app origin.
    ContentDisposition disposition =
        (DocumentAttachmentService.isInlineImage(metadata.contentType())
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
            .filename(metadata.fileName())
            .build();
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(cachePolicy())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(content.contentLength())
        .contentType(MediaType.parseMediaType(metadata.contentType()))
        .body(new InputStreamResource(content.stream()));
  }

  /** Attachments are immutable (content-addressed), so private caching may hold them long. */
  private static CacheControl cachePolicy() {
    return CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();
  }

  /** Adapts the multipart part to the framework-free {@link UploadSource} (ADR-0004). */
  private static UploadSource sourceOf(MultipartFile file) {
    return new UploadSource() {
      @Override
      public InputStream open() throws IOException {
        return file.getInputStream();
      }

      @Override
      public long declaredSize() {
        return file.getSize();
      }
    };
  }

  /** What the composer needs to insert the Markdown reference (image vs. link syntax). */
  public record AttachmentUploadResponse(
      UUID id, String url, String fileName, String contentType) {}
}
