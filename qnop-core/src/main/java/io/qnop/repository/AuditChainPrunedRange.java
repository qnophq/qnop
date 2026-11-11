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

/**
 * One run of consecutive chain positions removed by a sanctioned document cascade, as recorded by
 * the {@code audit.chain.pruned} tombstone event (issue #928, ADR-0043). {@code prevHash} is the
 * hash that led into the run, {@code lastHash} the hash it ended on; {@code recordedSeq} is the
 * tombstone's own chain position (null if it is somehow unsealed).
 */
public record AuditChainPrunedRange(
    Long recordedSeq, long fromSeq, long toSeq, String prevHash, String lastHash) {}
