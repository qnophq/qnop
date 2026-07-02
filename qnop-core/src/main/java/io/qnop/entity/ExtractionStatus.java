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
 * Lifecycle of a {@link DocumentVersion}'s canonical representation (issue #245, ADR-0032/0033):
 * {@code PENDING} until the async extraction job runs, then {@code READY} (rendered document
 * attached) or {@code FAILED} (the content itself is unprocessable — a re-upload starts a fresh
 * version, existing versions never change).
 */
public enum ExtractionStatus {
  PENDING,
  READY,
  FAILED
}
