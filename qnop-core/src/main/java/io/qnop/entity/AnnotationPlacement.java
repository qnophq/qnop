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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Where an {@link Annotation} sits on a specific {@link DocumentVersion} (issue #244, ADR-0009).
 * Because annotations have version-independent identity but documents change between versions, each
 * (annotation, version) pair gets its own placement — unique on {@code (annotation_id,
 * document_version_id)} in Liquibase (ADR-0020).
 *
 * <p>The {@code anchor} is an opaque jsonb descriptor (text-quote, coordinates, structural path —
 * ADR-0032) the re-anchoring job (#248) uses to relocate the annotation on a new version. The
 * {@link PlacementStatus} tracks that outcome; {@code @Version} guards the concurrent re-anchor
 * write.
 */
@Entity
@Table(name = "annotation_placement")
public class AnnotationPlacement {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "annotation_id", nullable = false, updatable = false)
  private UUID annotationId;

  @Column(name = "document_version_id", nullable = false, updatable = false)
  private UUID documentVersionId;

  /** Opaque jsonb anchor descriptor (ADR-0032); the re-anchoring job (#248) interprets it. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "anchor", nullable = false)
  private String anchor;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private PlacementStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected AnnotationPlacement() {
    // for JPA
  }

  /**
   * Creates a placement awaiting (re-)anchoring on the given version, {@link
   * PlacementStatus#PENDING}.
   */
  public AnnotationPlacement(UUID annotationId, UUID documentVersionId, String anchor) {
    this.annotationId = annotationId;
    this.documentVersionId = documentVersionId;
    this.anchor = anchor;
    this.status = PlacementStatus.PENDING;
  }

  /** Anchored cleanly, optionally refining the anchor to the resolved location. */
  public void markPlaced(String resolvedAnchor) {
    this.anchor = resolvedAnchor;
    this.status = PlacementStatus.PLACED;
  }

  /** Anchored, but the location shifted; records the new anchor. */
  public void markMoved(String newAnchor) {
    this.anchor = newAnchor;
    this.status = PlacementStatus.MOVED;
  }

  /** The anchored content is gone from this version; needs human attention. */
  public void markOrphaned() {
    this.status = PlacementStatus.ORPHANED;
  }

  /** Re-anchoring could not be completed. */
  public void markFailed() {
    this.status = PlacementStatus.FAILED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAnnotationId() {
    return annotationId;
  }

  public UUID getDocumentVersionId() {
    return documentVersionId;
  }

  public String getAnchor() {
    return anchor;
  }

  public PlacementStatus getStatus() {
    return status;
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
    if (!(o instanceof AnnotationPlacement other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
