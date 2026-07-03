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
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';

/**
 * The review surface's two presentations (issue #291): the always-visible
 * annotation panel, or the full-width focus mode with the spotlight overlay.
 */
export type ReviewViewMode = 'panel' | 'focus';

const VIEW_MODE_KEY = 'qnop-review-view-mode';

function storedMode(): ReviewViewMode | null {
  try {
    const value = localStorage.getItem(VIEW_MODE_KEY);
    return value === 'panel' || value === 'focus' ? value : null;
  } catch {
    return null;
  }
}

/**
 * The persisted view mode (a personal preference, so localStorage rather than
 * the URL). Without a stored choice, small viewports default to focus mode —
 * exactly where the panel-below-document layout is weakest (issue #291).
 */
export function useViewMode(): [ReviewViewMode, (mode: ReviewViewMode) => void] {
  const theme = useTheme();
  const smallViewport = useMediaQuery(theme.breakpoints.down('md'));
  const [stored, setStored] = useState<ReviewViewMode | null>(storedMode);

  const setMode = (mode: ReviewViewMode) => {
    setStored(mode);
    try {
      localStorage.setItem(VIEW_MODE_KEY, mode);
    } catch {
      // best-effort persistence
    }
  };

  return [stored ?? (smallViewport ? 'focus' : 'panel'), setMode];
}
