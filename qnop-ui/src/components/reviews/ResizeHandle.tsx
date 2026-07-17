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

import Box from '@mui/material/Box';
import { alpha, useTheme } from '@mui/material/styles';
import type { ResizeHandleState } from './useResizeHandle';

interface ResizeHandleProps {
  ariaLabel: string;
  testId?: string;
  minWidth: number;
  state: ResizeHandleState;
}

/**
 * The grab handle itself: a focusable separator on the surface's leading edge
 * with a quiet grip hairline that wakes on hover/drag. The host positions it
 * inside a `position: relative` (or overflow-visible) container.
 */
export function ResizeHandle({ ariaLabel, testId, minWidth, state }: ResizeHandleProps) {
  const theme = useTheme();
  return (
    <Box
      role="separator"
      aria-orientation="vertical"
      aria-label={ariaLabel}
      aria-valuemin={minWidth}
      aria-valuenow={Math.round(state.width)}
      tabIndex={0}
      data-testid={testId}
      {...state.handleProps}
      sx={{
        position: 'absolute',
        left: -5,
        top: 0,
        bottom: 0,
        width: 10,
        zIndex: 1,
        display: { xs: 'none', sm: 'flex' },
        alignItems: 'center',
        justifyContent: 'center',
        cursor: 'col-resize',
        touchAction: 'none',
        '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
        '&::after': {
          content: '""',
          width: '3px',
          height: 48,
          borderRadius: 2,
          bgcolor: state.resizing ? theme.qnop.brand.blue : theme.palette.divider,
          transition: 'background-color 120ms ease, height 120ms ease',
        },
        '&:hover::after': {
          bgcolor: state.resizing ? theme.qnop.brand.blue : alpha(theme.qnop.brand.blue, 0.6),
          height: 72,
        },
        '@media (prefers-reduced-motion: reduce)': { '&::after': { transition: 'none' } },
      }}
    />
  );
}
