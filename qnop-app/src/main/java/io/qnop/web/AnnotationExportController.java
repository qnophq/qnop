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

import io.qnop.service.review.AnnotationExportService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Downloads a review's annotations as an Excel workbook (issue #547).
 *
 * <p>A plain controller by design (ADR-0028), like {@code DocumentContentController}: binary
 * downloads stay outside the generated OpenAPI contract, which describes JSON only. Authorization
 * is not re-implemented here — the service reads through {@code AnnotationService.list}, so a
 * caller who cannot see the review cannot export it, and an anonymous review exports pseudonyms
 * (ADR-0038).
 *
 * <p>Deliberately not cached: annotations change constantly and the workbook is cheap to rebuild,
 * so there is no content hash to hang an ETag on the way a version download does.
 */
@RestController
public class AnnotationExportController {

  private static final String XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final AnnotationExportService exports;

  public AnnotationExportController(AnnotationExportService exports) {
    this.exports = exports;
  }

  // mounted under /api/v1 by ApiPathConfig
  @GetMapping("/documents/{documentId}/annotations/export")
  public ResponseEntity<InputStreamResource> exportAnnotations(
      @PathVariable UUID documentId,
      @RequestParam(value = "version", required = false) Integer version,
      // Repeated or comma-separated; empty means every column (the wizard's default).
      @RequestParam(value = "fields", required = false) List<String> fields,
      @RequestParam(value = "scope", required = false, defaultValue = "all") String scope) {
    AnnotationExportService.Export export =
        exports.export(
            documentId,
            version,
            new AnnotationExportService.ExportRequest(fields == null ? List.of() : fields, scope),
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin());

    ContentDisposition disposition =
        ContentDisposition.attachment()
            .filename(DownloadFilename.forAnnotationExport(export.documentTitle()))
            .build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentLength(export.workbook().length)
        .contentType(MediaType.parseMediaType(XLSX))
        .body(new InputStreamResource(new ByteArrayInputStream(export.workbook())));
  }
}
