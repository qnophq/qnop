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

import { useState } from 'react';

const RAIL_COLLAPSED_KEY = 'qnop-compare-rail-collapsed';

function storedCollapsed(): boolean {
  try {
    return localStorage.getItem(RAIL_COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * The persisted collapsed state of the comparison's changes rail (issue #369):
 * a personal reading preference like the other viewer choices, so localStorage
 * rather than the URL. Defaults to expanded.
 */
export function useRailCollapsed(): [boolean, (collapsed: boolean) => void] {
  const [collapsed, setCollapsed] = useState(storedCollapsed);

  const set = (value: boolean) => {
    setCollapsed(value);
    try {
      localStorage.setItem(RAIL_COLLAPSED_KEY, value ? '1' : '0');
    } catch {
      // best-effort persistence
    }
  };

  return [collapsed, set];
}
