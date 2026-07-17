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

import io.qnop.api.v1.endpoint.AuditApi;
import io.qnop.api.v1.model.AuditEvent;
import io.qnop.api.v1.model.AuditEventListResponse;
import io.qnop.service.audit.AuditLogService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The organisation-wide audit trail ({@code GET /api/v1/audit/events}), implementing the generated
 * {@link AuditApi} contract (issue #466, ADR-0041) — a thin mapping over {@link AuditLogService}.
 * Authorization is enforced centrally by the security chain ({@code /api/v1/audit/**} requires
 * {@code AUDITOR} or {@code ADMIN}); a {@code MEMBER} never reaches this method (403). The query is
 * org-wide by design, so no caller identity is needed here.
 */
@RestController
public class AuditController implements AuditApi {

  private final AuditLogService auditLog;

  public AuditController(AuditLogService auditLog) {
    this.auditLog = auditLog;
  }

  @Override
  public ResponseEntity<AuditEventListResponse> listAuditEvents(
      String eventType,
      UUID actorId,
      UUID documentId,
      OffsetDateTime from,
      OffsetDateTime to,
      Integer page,
      Integer size) {
    AuditLogService.AuditPage result =
        auditLog.list(
            eventType,
            actorId,
            documentId,
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant(),
            page,
            size);
    return ResponseEntity.ok(
        new AuditEventListResponse()
            .items(result.items().stream().map(AuditController::toDto).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  private static AuditEvent toDto(AuditLogService.AuditEventView view) {
    return new AuditEvent()
        .id(view.id())
        .eventType(view.eventType())
        .documentId(view.documentId())
        .documentTitle(view.documentTitle())
        .documentSlug(view.documentSlug())
        .actorId(view.actorId())
        .actorDisplayName(view.actorDisplayName())
        .detail(view.detail())
        .createdAt(view.createdAt() == null ? null : view.createdAt().atOffset(ZoneOffset.UTC));
  }
}
