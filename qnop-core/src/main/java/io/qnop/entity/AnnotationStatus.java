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

/**
 * The lifecycle of an {@link Annotation} (issue #244, ADR-0011 as amended by #405). A review is
 * finalizable once no annotation is still {@link #OPEN}. This is a closed set, pinned by a Postgres
 * {@code CHECK} in Liquibase (ADR-0020).
 */
public enum AnnotationStatus {
  /** Raised by a reviewer and awaiting a response; the concern is not yet settled. */
  OPEN,
  /** The author considers their concern settled and closed the annotation (issue #405). */
  RESOLVED
}
