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

/**
 * Resolves the version to show: the requested one when any loaded source (the
 * document detail's `latestVersionNumber` or the version list itself) already
 * knows it, otherwise the highest known version. Clamping against BOTH sources
 * closes the stale-detail window right after a new-version upload, where the
 * URL says `?version=2` but the cached detail still reports 1 (issue #300).
 */
export function resolveEffectiveVersion(
  requestedVersion: number,
  latestFromDetail: number,
  knownVersionNumbers: number[],
): number | undefined {
  const maxKnown = Math.max(latestFromDetail, ...knownVersionNumbers, 0);
  if (requestedVersion >= 1 && requestedVersion <= maxKnown) return requestedVersion;
  return maxKnown >= 1 ? maxKnown : undefined;
}
