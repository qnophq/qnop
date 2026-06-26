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
import io.qnop.service.avatar.AvatarService;
import io.qnop.service.avatar.AvatarStorage.AvatarContent;
import io.qnop.service.avatar.AvatarValidationException;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
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
 * Profile-avatar endpoints (issue #117). The self surface ({@code /users/me/avatar}) is gated to
 * the JWT subject; the admin surface ({@code /admin/users/{userId}/avatar}) is admin-only (enforced
 * centrally in the security chain); the read path ({@code GET /users/{userId}/avatar}) is public so
 * an {@code <img>} element can load it without a bearer token (ADR-0031).
 *
 * <p>Like {@code BrandingController}, these are deliberately hand-written rather than generated
 * from the OpenAPI JSON contract: they carry multipart uploads and binary responses with ETag/304
 * caching, which sit outside the published JSON surface (ADR-0028). The {@code @RestController}
 * mappings are mounted under {@code /api/v1} by {@link ApiPathConfig}.
 */
@RestController
public class AvatarController {

  private final AvatarService avatars;

  public AvatarController(AvatarService avatars) {
    this.avatars = avatars;
  }

  @PostMapping(value = "/users/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AvatarUploadResponse> uploadMine(@RequestParam("file") MultipartFile file)
      throws IOException {
    UUID me = CurrentUser.requireUserId();
    Instant updatedAt = avatars.store(me, file.getBytes(), me);
    return ResponseEntity.ok(new AvatarUploadResponse(AvatarUrls.forUser(me, updatedAt)));
  }

  @DeleteMapping("/users/me/avatar")
  public ResponseEntity<Void> deleteMine() {
    avatars.remove(CurrentUser.requireUserId());
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      value = "/admin/users/{userId}/avatar",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AvatarUploadResponse> uploadForUser(
      @PathVariable UUID userId, @RequestParam("file") MultipartFile file) throws IOException {
    Instant updatedAt = avatars.store(userId, file.getBytes(), CurrentUser.requireUserId());
    return ResponseEntity.ok(new AvatarUploadResponse(AvatarUrls.forUser(userId, updatedAt)));
  }

  @DeleteMapping("/admin/users/{userId}/avatar")
  public ResponseEntity<Void> deleteForUser(@PathVariable UUID userId) {
    avatars.remove(userId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/users/{userId}/avatar")
  public ResponseEntity<byte[]> serve(
      @PathVariable UUID userId,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    AvatarContent avatar =
        avatars.get(userId).orElseThrow(() -> AvatarValidationException.notFound("no avatar"));
    String etag = "\"" + avatar.sha256() + "\"";
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(304).eTag(etag).cacheControl(CacheControl.noCache()).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(CacheControl.noCache())
        .contentType(MediaType.parseMediaType(avatar.contentType()))
        .body(avatar.content());
  }

  @ExceptionHandler(AvatarValidationException.class)
  public ResponseEntity<ErrorResponse> onAvatarError(AvatarValidationException ex) {
    return ResponseEntity.status(ex.getStatus())
        .body(
            new ErrorResponse()
                .code(ex.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }

  /** The new avatar URL after an upload, so the SPA can update the avatar without a refetch. */
  public record AvatarUploadResponse(String avatarUrl) {}
}
