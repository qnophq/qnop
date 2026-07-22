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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A point raised against a {@link Document} during review (issue #244, ADR-0011). Identity is
 * <em>version-independent</em>: the annotation belongs to the document, while <em>where</em> it
 * sits in each version is a separate {@link AnnotationPlacement} (re-anchored per version,
 * ADR-0009). Its discussion is the {@link Comment} thread keyed by this annotation.
 *
 * <p>A review is finalizable once no annotation is still {@link AnnotationStatus#OPEN}. The
 * annotation's author closes it via {@link #resolve()} once their concern is settled (issue #405);
 * {@code @Version} guards concurrent resolutions (ADR-0030).
 */
@Entity
@Table(name = "annotation")
public class Annotation {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private AnnotationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", length = 16)
  private AnnotationType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", length = 16)
  private AnnotationPriority priority;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected Annotation() {
    // for JPA
  }

  /** Raises a new annotation, {@link AnnotationStatus#OPEN}, authored by {@code authorId}. */
  public Annotation(UUID documentId, UUID authorId) {
    this.documentId = documentId;
    this.authorId = authorId;
    this.status = AnnotationStatus.OPEN;
  }

  /** The author's concern is settled; the annotation is closed (issue #405). */
  public void resolve() {
    this.status = AnnotationStatus.RESOLVED;
  }

  /** Reopens a previously settled annotation (e.g. the concern resurfaced). */
  public void reopen() {
    this.status = AnnotationStatus.OPEN;
  }

  /** The owner/admin dismissed the concern over the author's head (issue #408). */
  public void dismiss() {
    this.status = AnnotationStatus.DISMISSED;
  }

  /** (Re)classifies the point — both facets optional, {@code null} clears (issue #392). */
  public void classify(AnnotationType type, AnnotationPriority priority) {
    this.type = type;
    this.priority = priority;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getAuthorId() {
    return authorId;
  }

  public AnnotationStatus getStatus() {
    return status;
  }

  public AnnotationType getType() {
    return type;
  }

  public AnnotationPriority getPriority() {
    return priority;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Annotation other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
