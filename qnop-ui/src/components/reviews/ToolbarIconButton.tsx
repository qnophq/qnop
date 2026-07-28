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

import type { ReactNode } from 'react';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Tooltip from '@mui/material/Tooltip';

/**
 * An icon-only action in a review toolbar — the shape the Tasks toolbar and the
 * annotation panel both use, defined once so the four of them cannot drift in
 * size, spacing or hover behaviour.
 *
 * <p>The label is not decoration: it is the button's <em>accessible name</em>
 * (`aria-label`) as well as its tooltip. Dropping the visible text may not cost
 * a screen-reader user anything, and it must not — an icon-only control with no
 * name is an unlabelled button.
 *
 * <p>The tooltip wraps a `span` because a disabled MUI button fires no pointer
 * events, and a tooltip on it would simply never appear while busy.
 */
export function ToolbarIconButton({
  label,
  icon,
  onClick,
  variant = 'outlined',
  busy = false,
  disabled = false,
}: {
  label: string;
  icon: ReactNode;
  onClick: () => void;
  variant?: 'outlined' | 'contained';
  busy?: boolean;
  disabled?: boolean;
}) {
  return (
    <Tooltip title={label}>
      <span style={{ display: 'inline-flex', flexShrink: 0 }}>
        <Button
          size="small"
          variant={variant}
          onClick={onClick}
          disabled={disabled || busy}
          aria-label={label}
          sx={{ minWidth: 36, px: 1 }}
        >
          {busy ? <CircularProgress size={14} color="inherit" /> : icon}
        </Button>
      </span>
    </Tooltip>
  );
}
