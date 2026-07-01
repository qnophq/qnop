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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One message in the discussion thread of an {@link Annotation} (issue #244, ADR-0011). Comments
 * are ordered by {@code createdAt} to form the thread and are append-only in this iteration.
 * Deleting the annotation cascades its comments (enforced in Liquibase, ADR-0020).
 */
@Entity
@Table(name = "comment")
public class Comment {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "annotation_id", nullable = false, updatable = false)
  private UUID annotationId;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Column(name = "body", nullable = false, columnDefinition = "TEXT")
  private String body;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Comment() {
    // for JPA
  }

  public Comment(UUID annotationId, UUID authorId, String body) {
    this.annotationId = annotationId;
    this.authorId = authorId;
    this.body = body;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAnnotationId() {
    return annotationId;
  }

  public UUID getAuthorId() {
    return authorId;
  }

  public String getBody() {
    return body;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Comment other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
