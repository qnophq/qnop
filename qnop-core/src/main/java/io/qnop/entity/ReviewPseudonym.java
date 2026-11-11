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
import java.util.UUID;

/**
 * A persisted "Participant N" ordinal of one principal in one anonymous review (issue #914,
 * ADR-0038 amendment). Write-once: an assigned ordinal never changes, so a label stays stable while
 * further contributors join. Rows are inserted race-free via {@code
 * ReviewPseudonymRepository#insertIfAbsent}; this mapping exists for reads.
 */
@Entity
@Table(name = "review_pseudonym")
public class ReviewPseudonym {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  /** A user id, or an account-less participant's row id (ADR-0061) — hence no user FK. */
  @Column(name = "principal_id", nullable = false, updatable = false)
  private UUID principalId;

  @Column(name = "ordinal", nullable = false, updatable = false)
  private int ordinal;

  protected ReviewPseudonym() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getPrincipalId() {
    return principalId;
  }

  public int getOrdinal() {
    return ordinal;
  }
}
