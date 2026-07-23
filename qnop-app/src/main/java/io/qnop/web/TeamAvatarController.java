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
import io.qnop.service.TeamAccessForbiddenException;
import io.qnop.service.avatar.AvatarLimits;
import io.qnop.service.avatar.AvatarStorage.AvatarContent;
import io.qnop.service.avatar.AvatarValidationException;
import io.qnop.service.avatar.TeamAvatarService;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
 * Team-avatar endpoints (issue #509), the team counterpart of {@link AvatarController}. The admin
 * surface ({@code /admin/teams/{teamId}/avatar}) is admin-only (enforced centrally in the security
 * chain); the self-manage surface ({@code /teams/{teamId}/avatar}) is gated in-handler to a team
 * {@code LEAD} or an admin (issue #470 pattern); the read path ({@code GET /teams/{teamId}/avatar})
 * is public so an {@code <img>} can load it without a bearer token.
 *
 * <p>Like {@code AvatarController}, these are hand-written rather than generated from the OpenAPI
 * JSON contract: multipart uploads + binary responses with ETag/304 sit outside the JSON surface
 * (ADR-0028). Mounted under {@code /api/v1} by {@link ApiPathConfig}.
 */
@RestController
public class TeamAvatarController {

  private final TeamAvatarService avatars;

  public TeamAvatarController(TeamAvatarService avatars) {
    this.avatars = avatars;
  }

  // --- Admin surface (admin-only via the security chain) -------------------

  @PostMapping(
      value = "/admin/teams/{teamId}/avatar",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AvatarUploadResponse> uploadForTeam(
      @PathVariable UUID teamId, @RequestParam("file") MultipartFile file) throws IOException {
    requireWithinCap(file);
    Instant updatedAt = avatars.store(teamId, file.getBytes(), CurrentUser.requireUserId());
    return ResponseEntity.ok(new AvatarUploadResponse(AvatarUrls.forTeam(teamId, updatedAt)));
  }

  @DeleteMapping("/admin/teams/{teamId}/avatar")
  public ResponseEntity<Void> deleteForTeam(@PathVariable UUID teamId) {
    avatars.remove(teamId);
    return ResponseEntity.noContent().build();
  }

  // --- Self-manage surface (team LEAD or admin, gated in-handler) ----------

  @PostMapping(value = "/teams/{teamId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AvatarUploadResponse> uploadForMyTeam(
      @PathVariable UUID teamId, @RequestParam("file") MultipartFile file) throws IOException {
    UUID actor = CurrentUser.requireUserId();
    avatars.requireLeadOrAdmin(teamId, actor, CurrentUser.isAdmin());
    requireWithinCap(file);
    Instant updatedAt = avatars.store(teamId, file.getBytes(), actor);
    return ResponseEntity.ok(new AvatarUploadResponse(AvatarUrls.forTeam(teamId, updatedAt)));
  }

  @DeleteMapping("/teams/{teamId}/avatar")
  public ResponseEntity<Void> deleteForMyTeam(@PathVariable UUID teamId) {
    avatars.requireLeadOrAdmin(teamId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    avatars.remove(teamId);
    return ResponseEntity.noContent().build();
  }

  // --- Public read path ----------------------------------------------------

  @GetMapping("/teams/{teamId}/avatar")
  public ResponseEntity<byte[]> serve(
      @PathVariable UUID teamId,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    AvatarContent avatar =
        avatars.get(teamId).orElseThrow(() -> AvatarValidationException.notFound("no avatar"));
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

  /** Rejects an oversized upload before {@code getBytes()} materializes it on the heap. */
  private static void requireWithinCap(MultipartFile file) {
    if (file.getSize() > AvatarLimits.MAX_SIZE_BYTES) {
      throw AvatarValidationException.tooLarge(
          "avatar exceeds " + AvatarLimits.MAX_SIZE_BYTES + " bytes");
    }
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

  @ExceptionHandler(TeamAccessForbiddenException.class)
  public ResponseEntity<ErrorResponse> onForbidden(TeamAccessForbiddenException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new ErrorResponse()
                .code("TEAM_ACCESS_FORBIDDEN")
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }

  /** The new avatar URL after an upload, so the SPA can update without a refetch. */
  public record AvatarUploadResponse(String avatarUrl) {}
}
