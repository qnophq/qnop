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
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A participant's last visit of a review (issue #307): one row per (document, user), stamped when a
 * review page opens. Powers the unseen markers — everything created after the PREVIOUS visit reads
 * as new. Personal by design: team members carry their own stamp, never the team principal.
 */
@Entity
@Table(name = "review_visit")
public class ReviewVisit {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected ReviewVisit() {
    // for JPA
  }

  public ReviewVisit(UUID documentId, UUID userId, Instant lastSeenAt) {
    this.documentId = documentId;
    this.userId = userId;
    this.lastSeenAt = lastSeenAt;
  }

  /** Moves the stamp forward and returns the previous value (the marker baseline). */
  public Instant stamp(Instant now) {
    Instant previous = this.lastSeenAt;
    this.lastSeenAt = now;
    return previous;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }
}
