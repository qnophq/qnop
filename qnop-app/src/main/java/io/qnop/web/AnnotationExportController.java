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
import io.qnop.service.review.export.AnnotationExportFormat;
import io.qnop.service.review.export.ExportDateFormat;
import io.qnop.service.review.export.ExportFilename;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Downloads a review's annotations as a file (issues #547, #635).
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
      @RequestParam(value = "scope", required = false, defaultValue = "all") String scope,
      // A query parameter rather than a sibling path, so the links that shipped
      // with #547 keep working and every format shares one authorization seam.
      @RequestParam(value = "format", required = false) String format,
      // Off unless asked for: the comment sheet costs a query round per annotation.
      @RequestParam(value = "comments", required = false, defaultValue = "false")
          boolean includeComments,
      // On unless refused: a branded report is the common case, and a format that
      // cannot carry an image ignores this anyway.
      @RequestParam(value = "logo", required = false, defaultValue = "true") boolean includeLogo,
      @RequestParam(value = "dateFormat", required = false) String dateFormat,
      // The UI preselects the reader's own zone (ADR-0041) and always sends it.
      // A request that names none gets UTC — a predictable wire default beats
      // resolving a caller's preference behind their back on a raw API call.
      @RequestParam(value = "timezone", required = false) String timezone,
      // Sanitized rather than trusted: this lands in a response header, where a
      // newline would be header injection and a slash an escape from the
      // downloads folder.
      @RequestParam(value = "filename", required = false) String filename) {
    AnnotationExportService.Export export =
        exports.export(
            documentId,
            version,
            new AnnotationExportService.ExportRequest(
                AnnotationExportFormat.fromId(format),
                fields == null ? List.of() : fields,
                scope,
                includeComments,
                includeLogo,
                ExportDateFormat.fromId(dateFormat),
                zoneOrUtc(timezone),
                requestOrigin()),
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin());

    ContentDisposition disposition =
        ContentDisposition.attachment()
            .filename(
                ExportFilename.forExport(filename, export.documentTitle(), export.format()),
                StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentLength(export.content().length)
        .contentType(MediaType.parseMediaType(export.format().getContentType()))
        .body(new InputStreamResource(new ByteArrayInputStream(export.content())));
  }

  /**
   * The scheme and authority this request arrived on, e.g. {@code https://qnop.example.com}.
   *
   * <p>Used only as a fallback for attachment links when {@code general.base_url} is unset. Taken
   * from the request rather than guessed: it is the origin the caller is already talking to, so the
   * link works for them. It is not used for anything that leaves the app on qnop's behalf — a
   * notification mail deliberately uses the configured setting only, because its link is followed
   * by someone who did not make the request and the {@code Host} header is theirs to forge.
   */
  private static String requestOrigin() {
    try {
      return ServletUriComponentsBuilder.fromCurrentRequest()
          .replacePath(null)
          .replaceQuery(null)
          .build()
          .toUriString();
    } catch (IllegalStateException e) {
      // No request bound (a test calling the service directly, say).
      return null;
    }
  }

  /**
   * The requested zone, or UTC.
   *
   * <p>An unusable id falls back rather than failing, like every other malformed export parameter:
   * the caller wants a file, and a timestamp in the wrong zone is a smaller failure than no export.
   */
  private static ZoneId zoneOrUtc(String timezone) {
    if (timezone == null || timezone.isBlank()) {
      return ZoneOffset.UTC;
    }
    try {
      return ZoneId.of(timezone.trim());
    } catch (DateTimeException e) {
      return ZoneOffset.UTC;
    }
  }
}
