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

import io.qnop.api.v1.endpoint.DashboardApi;
import io.qnop.api.v1.model.DashboardActivity;
import io.qnop.api.v1.model.DashboardReply;
import io.qnop.api.v1.model.DashboardResponse;
import io.qnop.api.v1.model.DashboardStats;
import io.qnop.service.avatar.AvatarService;
import io.qnop.service.review.DashboardService;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's cross-review aggregates (issue #454), implementing the generated {@link
 * DashboardApi} contract — a thin mapping over {@link DashboardService}.
 */
@RestController
public class DashboardController implements DashboardApi {

  private final DashboardService dashboard;
  private final AvatarService avatars;

  public DashboardController(DashboardService dashboard, AvatarService avatars) {
    this.dashboard = dashboard;
    this.avatars = avatars;
  }

  @Override
  public ResponseEntity<DashboardResponse> getDashboard() {
    DashboardService.DashboardView view = dashboard.overview(CurrentUser.requireUserId());
    return ResponseEntity.ok(
        new DashboardResponse()
            .replies(view.replies().stream().map(this::toDto).toList())
            .activity(view.activity().stream().map(this::toDto).toList())
            .stats(new DashboardStats().resolvedThisWeek(view.resolvedThisWeek())));
  }

  private DashboardReply toDto(DashboardService.ReplyView view) {
    return new DashboardReply()
        .commentId(view.commentId())
        .annotationId(view.annotationId())
        .documentId(view.documentId())
        .documentTitle(view.documentTitle())
        .documentSlug(view.documentSlug())
        .authorId(view.authorId())
        .authorAvatarUrl(avatarUrlOf(view.authorId()))
        .authorDisplayName(view.authorDisplayName())
        .body(view.body())
        .annotationExcerpt(view.annotationExcerpt())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC));
  }

  private DashboardActivity toDto(DashboardService.ActivityView view) {
    return new DashboardActivity()
        .type(view.type())
        .documentId(view.documentId())
        .documentTitle(view.documentTitle())
        .documentSlug(view.documentSlug())
        .actorId(view.actorId())
        .actorAvatarUrl(avatarUrlOf(view.actorId()))
        .actorDisplayName(view.actorDisplayName())
        .createdAt(view.createdAt().atOffset(ZoneOffset.UTC));
  }

  /** Null for anonymised identities (no id) and for users without an uploaded avatar. */
  private String avatarUrlOf(UUID userId) {
    if (userId == null) {
      return null;
    }
    return AvatarUrls.forUser(userId, avatars.updatedAt(userId).orElse(null));
  }
}
