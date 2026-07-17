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
package io.qnop.service.audit;

import static java.util.stream.Collectors.toMap;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.UserDisplayName;
import io.qnop.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisation-wide audit list for the AUDITOR/ADMIN compliance view (issue #466, ADR-0041): a
 * paginated, newest-first, filterable read of the append-only {@code audit_event} trail across
 * every document. Authorization is enforced at the security chain ({@code /api/v1/audit/**}
 * requires {@code AUDITOR} or {@code ADMIN}) — this service is deliberately un-scoped by
 * participation.
 *
 * <p>Unlike the dashboard feed (issue #454), this view reports the <em>real</em> actor identity: it
 * resolves actor ids through a direct {@link UserRepository#findDisplayNamesByIdIn} batch lookup,
 * <em>not</em> through {@code ReviewIdentityResolver}, whose per-review pseudonymity (ADR-0038)
 * must not obstruct compliance. The system actor ({@code actorId == null}) renders as {@code
 * "System"}. The mapping is a DB-free static function ({@link #toViews}) so it is unit-testable
 * without an {@code EntityManager} (the architecture guardrail).
 */
@Service
public class AuditLogService {

  /** Defensive upper bound on page size, mirroring the OpenAPI contract's {@code maximum}. */
  public static final int MAX_PAGE_SIZE = 100;

  public static final int DEFAULT_PAGE_SIZE = 20;

  /** Display name for events with no acting user (a system-generated event). */
  static final String SYSTEM_ACTOR_NAME = "System";

  private final AuditEventRepository auditEvents;
  private final UserRepository users;
  private final DocumentRepository documents;

  public AuditLogService(
      AuditEventRepository auditEvents, UserRepository users, DocumentRepository documents) {
    this.auditEvents = auditEvents;
    this.users = users;
    this.documents = documents;
  }

  /**
   * One audit entry with the actor and document resolved to their real display names. {@code
   * actorId} is null for a system event; {@code actorDisplayName} is then {@code "System"}, and
   * null only when a real actor's user row no longer resolves. {@code detail} is the raw jsonb
   * string.
   */
  public record AuditEventView(
      UUID id,
      String eventType,
      UUID documentId,
      String documentTitle,
      String documentSlug,
      UUID actorId,
      String actorDisplayName,
      String detail,
      Instant createdAt) {}

  public record AuditPage(List<AuditEventView> items, long total, int page, int size) {}

  /**
   * A page of the org-wide audit trail, newest first. Every filter is optional; {@code page}/{@code
   * size} are clamped ({@code page} to {@code >= 0}, {@code size} to {@code [1, MAX_PAGE_SIZE]}),
   * so even a caller bypassing the OpenAPI bounds cannot request an unbounded page. A blank {@code
   * eventType} is treated as absent.
   */
  @Transactional(readOnly = true)
  public AuditPage list(
      String eventType,
      UUID actorId,
      UUID documentId,
      Instant from,
      Instant to,
      Integer page,
      Integer size) {
    int pageIndex = page == null || page < 0 ? 0 : page;
    int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    String filterEventType = eventType == null || eventType.isBlank() ? null : eventType;

    Page<AuditEvent> result =
        auditEvents.findFiltered(
            filterEventType,
            actorId,
            documentId,
            from,
            to,
            PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

    return new AuditPage(
        enrich(result.getContent()), result.getTotalElements(), pageIndex, pageSize);
  }

  /** Batch-resolves actor names and document metadata for a page, then maps to views. */
  private List<AuditEventView> enrich(List<AuditEvent> events) {
    if (events.isEmpty()) {
      return List.of();
    }
    List<UUID> actorIds =
        events.stream().map(AuditEvent::getActorId).filter(Objects::nonNull).distinct().toList();
    Map<UUID, String> actorNames =
        actorIds.isEmpty()
            ? Map.of()
            : users.findDisplayNamesByIdIn(actorIds).stream()
                .collect(toMap(UserDisplayName::id, UserDisplayName::displayName));
    List<UUID> documentIds = events.stream().map(AuditEvent::getDocumentId).distinct().toList();
    Map<UUID, Document> documentById =
        documents.findAllById(documentIds).stream()
            .collect(toMap(Document::getId, document -> document));
    return toViews(events, actorNames, documentById);
  }

  /**
   * The pure mapping from persisted events to views, given the pre-resolved name and document
   * lookups — DB-free so the anonymity-bypass and system-actor rules are unit-testable in
   * isolation.
   */
  static List<AuditEventView> toViews(
      List<AuditEvent> events, Map<UUID, String> actorNames, Map<UUID, Document> documentById) {
    return events.stream().map(event -> toView(event, actorNames, documentById)).toList();
  }

  private static AuditEventView toView(
      AuditEvent event, Map<UUID, String> actorNames, Map<UUID, Document> documentById) {
    Document document = documentById.get(event.getDocumentId());
    UUID actorId = event.getActorId();
    String actorDisplayName = actorId == null ? SYSTEM_ACTOR_NAME : actorNames.get(actorId);
    return new AuditEventView(
        event.getId(),
        event.getEventType(),
        event.getDocumentId(),
        document == null ? null : document.getTitle(),
        document == null ? null : document.getSlug(),
        actorId,
        actorDisplayName,
        event.getDetail(),
        event.getCreatedAt());
  }
}
