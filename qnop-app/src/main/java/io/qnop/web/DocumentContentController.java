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

import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentAccessService.OriginalDownload;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Streams a version's original binary through the server with per-request authorization (issue
 * #245, ADR-0032 §5) — never via presigned URLs, so a review document is only reachable by its
 * participants. A plain controller by design (ADR-0028): binary downloads stay outside the
 * generated OpenAPI contract. The {@code ETag} is the immutable content hash; versions never
 * change, so a matching {@code If-None-Match} short-circuits to 304.
 */
@RestController
public class DocumentContentController {

  private final DocumentAccessService documents;

  public DocumentContentController(DocumentAccessService documents) {
    this.documents = documents;
  }

  // mounted under /api/v1 by ApiPathConfig
  @GetMapping("/documents/{documentId}/versions/{versionNumber}/original")
  public ResponseEntity<InputStreamResource> downloadOriginal(
      @PathVariable UUID documentId,
      @PathVariable int versionNumber,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    UUID actor = CurrentUser.requireUserId();
    boolean admin = CurrentUser.isAdmin();

    OriginalDownload download = documents.getOriginal(documentId, versionNumber, actor, admin);
    String etag = "\"" + download.contentHash() + "\"";
    if (etag.equals(ifNoneMatch)) {
      download.close();
      return ResponseEntity.status(304).eTag(etag).build();
    }
    ContentDisposition disposition =
        ContentDisposition.inline()
            .filename(download.title() + "-v" + download.versionNumber() + ".pdf")
            .build();
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(CacheControl.noCache().cachePrivate())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentLength(download.contentLength())
        .contentType(MediaType.parseMediaType(download.contentType()))
        .body(new InputStreamResource(download.stream()));
  }
}
