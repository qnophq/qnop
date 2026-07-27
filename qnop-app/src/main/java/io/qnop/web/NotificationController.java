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

import io.qnop.api.v1.endpoint.NotificationsApi;
import io.qnop.api.v1.model.NotificationDetail;
import io.qnop.api.v1.model.NotificationPage;
import io.qnop.api.v1.model.NotificationSummary;
import io.qnop.api.v1.model.NotificationType;
import io.qnop.api.v1.model.UnreadNotificationCount;
import io.qnop.service.notification.NotificationService;
import io.qnop.service.notification.NotificationService.NotificationView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own notification inbox (issue #538, ADR-0051).
 *
 * <p>Every endpoint is scoped to {@link CurrentUser#requireUserId()} — the id never travels in the
 * URL, so there is no shape of request that asks for somebody else's inbox. A notification id that
 * belongs to another user answers {@code 404} rather than {@code 403}: a "forbidden" would confirm
 * the id exists, and nothing here needs to distinguish the two.
 */
@RestController
public class NotificationController implements NotificationsApi {

  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @Override
  public ResponseEntity<NotificationPage> listNotifications(
      Boolean unread, Integer page, Integer size) {
    NotificationService.NotificationPageView result =
        notifications.list(CurrentUser.requireUserId(), unread, page, size);
    NotificationPage body =
        new NotificationPage()
            .total(result.total())
            .page(result.page())
            .size(result.size())
            .unreadTotal(result.unreadTotal());
    result.items().stream().map(NotificationController::toSummary).forEach(body::addItemsItem);
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<UnreadNotificationCount> getUnreadNotificationCount() {
    return ResponseEntity.ok(
        new UnreadNotificationCount()
            .unread(notifications.unreadCount(CurrentUser.requireUserId())));
  }

  @Override
  public ResponseEntity<NotificationDetail> getNotification(UUID notificationId) {
    return notifications
        .get(CurrentUser.requireUserId(), notificationId)
        .map(NotificationController::toDetail)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  @Override
  public ResponseEntity<Void> markNotificationRead(UUID notificationId) {
    boolean marked = notifications.markRead(CurrentUser.requireUserId(), notificationId);
    return marked
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }

  @Override
  public ResponseEntity<Void> markAllNotificationsRead() {
    notifications.markAllRead(CurrentUser.requireUserId());
    return ResponseEntity.noContent().build();
  }

  private static NotificationSummary toSummary(NotificationView view) {
    return new NotificationSummary()
        .id(view.id())
        .type(NotificationType.fromValue(view.type()))
        .title(view.title())
        .preview(view.preview())
        .actorName(view.actorName())
        .documentTitle(view.documentTitle())
        .actionPath(view.actionPath())
        .accessible(view.accessible())
        .readAt(atUtc(view.readAt()))
        .createdAt(atUtc(view.createdAt()));
  }

  private static NotificationDetail toDetail(NotificationView view) {
    return new NotificationDetail()
        .id(view.id())
        .type(NotificationType.fromValue(view.type()))
        .title(view.title())
        .body(view.body())
        .preview(view.preview())
        .actorName(view.actorName())
        .documentId(view.documentId())
        .documentTitle(view.documentTitle())
        .actionPath(view.actionPath())
        .actionLabel(view.actionLabel())
        .accessible(view.accessible())
        .readAt(atUtc(view.readAt()))
        .createdAt(atUtc(view.createdAt()));
  }

  /** Nullable {@link Instant} → {@link OffsetDateTime} at UTC for the wire model (#295). */
  private static OffsetDateTime atUtc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
