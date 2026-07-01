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
package io.qnop.repository;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for annotations raised against a document (issue #244, ADR-0011). */
public interface AnnotationRepository extends JpaRepository<Annotation, UUID> {

  /** All annotations on a document. */
  List<Annotation> findByDocumentId(UUID documentId);

  /** Annotations on a document in a given status. */
  List<Annotation> findByDocumentIdAndStatus(UUID documentId, AnnotationStatus status);

  /**
   * How many annotations on a document are in a given status — e.g. OPEN, for the finalize gate.
   */
  long countByDocumentIdAndStatus(UUID documentId, AnnotationStatus status);
}
