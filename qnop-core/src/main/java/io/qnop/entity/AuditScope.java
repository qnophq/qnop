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
 * Which stream an {@link AuditEvent} belongs to (issue #524, ADR-0043).
 *
 * <p>{@link #DOCUMENT} is the original per-document review trail (ADR-0011): the row always carries
 * a {@code documentId}. {@link #SYSTEM} is the org-level operator stream (scheduler toggles,
 * run-now, ...): the row never carries a {@code documentId}. The discriminator is explicit — a
 * reader never has to infer intent from a null document id — and a DB check constraint keeps the
 * two in lock-step.
 */
public enum AuditScope {
  DOCUMENT,
  SYSTEM
}
