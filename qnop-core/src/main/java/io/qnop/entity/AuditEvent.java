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
package io.qnop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * An append-only record of something that happened to a {@link Document} (issue #244, ADR-0011) —
 * the review's audit trail (versions uploaded, annotations resolved, workflow transitions, …).
 *
 * <p>{@code eventType} is an open string (not an enum) so new event kinds — including
 * enterprise-only ones — need no schema change. {@code actorId} is the acting user, or null for
 * system-generated events. {@code detail} is optional jsonb context. Rows are never updated;
 * deleting the document cascades its trail (enforced in Liquibase, ADR-0020).
 *
 * <p>The trail carries two coexisting {@link AuditScope scopes} (issue #524, ADR-0043): {@link
 * AuditScope#DOCUMENT} rows (the original per-document trail) always carry a {@code documentId};
 * {@link AuditScope#SYSTEM} rows (org-level operator actions, e.g. scheduler toggles) never do. Use
 * {@link #system(String, UUID, String)} to record the latter.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The document this event belongs to, or null for {@link AuditScope#SYSTEM} events. */
  @Column(name = "document_id", updatable = false)
  private UUID documentId;

  /**
   * Which stream the event belongs to; a DB check keeps it in lock-step with {@code documentId}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 16, updatable = false)
  private AuditScope scope;

  @Column(name = "event_type", nullable = false, length = 64, updatable = false)
  private String eventType;

  /** The acting user, or null for system-generated events. */
  @Column(name = "actor_id", updatable = false)
  private UUID actorId;

  /** Optional jsonb context for the event (e.g. old/new state). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "detail", updatable = false)
  private String detail;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditEvent() {
    // for JPA
  }

  /** A per-document event ({@link AuditScope#DOCUMENT}); {@code documentId} is required. */
  public AuditEvent(UUID documentId, String eventType, UUID actorId, String detail) {
    this.documentId = documentId;
    this.scope = AuditScope.DOCUMENT;
    this.eventType = eventType;
    this.actorId = actorId;
    this.detail = detail;
  }

  private AuditEvent(
      AuditScope scope, UUID documentId, String eventType, UUID actorId, String detail) {
    this.scope = scope;
    this.documentId = documentId;
    this.eventType = eventType;
    this.actorId = actorId;
    this.detail = detail;
  }

  /**
   * An org-level system event ({@link AuditScope#SYSTEM}) with no document — e.g. a scheduler
   * toggle or run-now. {@code actorId} is the acting admin, or null for a machine-initiated event.
   */
  public static AuditEvent system(String eventType, UUID actorId, String detail) {
    return new AuditEvent(AuditScope.SYSTEM, null, eventType, actorId, detail);
  }

  public UUID getId() {
    return id;
  }

  public AuditScope getScope() {
    return scope;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public String getEventType() {
    return eventType;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AuditEvent other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
