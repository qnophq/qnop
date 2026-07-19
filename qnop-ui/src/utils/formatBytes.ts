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

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

/**
 * Formats a byte count as a human-readable size ("2.4 MB", "412 KB", "1.1 GB"),
 * binary (1024) based. Whole bytes show no decimal; larger units keep one. An
 * em dash for an absent/invalid value, and "0 B" for zero.
 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || Number.isNaN(bytes) || bytes < 0) return '—';
  if (bytes === 0) return '0 B';
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1);
  const value = bytes / 1024 ** exponent;
  const rounded = exponent === 0 ? value : Math.round(value * 10) / 10;
  return `${rounded} ${UNITS[exponent]}`;
}
