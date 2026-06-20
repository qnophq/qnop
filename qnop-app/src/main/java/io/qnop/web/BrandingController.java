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

import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.service.branding.BrandingService;
import io.qnop.service.branding.BrandingValidationException;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Branding asset endpoints (issue #23). Admin upload/delete live under {@code
 * /api/v1/admin/branding/**} (admin, enforced centrally in the security filter chain); the read
 * path {@code GET /api/v1/branding/{slot}} is public (login page, OG metadata).
 *
 * <p>These are deliberately hand-written controllers rather than generated from the OpenAPI JSON
 * contract: they carry multipart uploads and binary responses with ETag/304 caching, which sit
 * outside the published JSON surface (ADR-0028). The {@code slot} is the kebab-case URL form (e.g.
 * {@code logo-light}); resolving it to a domain slot happens in the service, so this web layer
 * never touches the entity model.
 */
@RestController
public class BrandingController {

  private final BrandingService branding;

  public BrandingController(BrandingService branding) {
    this.branding = branding;
  }

  @PostMapping(value = "/admin/branding/{slot}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<BrandingService.StoredAsset> upload(
      @PathVariable String slot, @RequestParam("file") MultipartFile file) throws IOException {
    return ResponseEntity.ok(branding.store(slot, file.getBytes(), CurrentUser.requireUserId()));
  }

  @DeleteMapping("/admin/branding/{slot}")
  public ResponseEntity<Void> delete(@PathVariable String slot) {
    branding.delete(slot);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/branding/{slot}")
  public ResponseEntity<byte[]> serve(
      @PathVariable String slot,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    BrandingService.BrandingAsset asset =
        branding.get(slot).orElseThrow(() -> BrandingValidationException.notFound("no asset"));
    String etag = "\"" + asset.sha256() + "\"";
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(304).eTag(etag).cacheControl(CacheControl.noCache()).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(CacheControl.noCache())
        .contentType(MediaType.parseMediaType(asset.contentType()))
        .body(asset.content());
  }

  @ExceptionHandler(BrandingValidationException.class)
  public ResponseEntity<ErrorResponse> onBrandingError(BrandingValidationException ex) {
    return ResponseEntity.status(ex.getStatus())
        .body(
            new ErrorResponse()
                .code(ex.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }
}
