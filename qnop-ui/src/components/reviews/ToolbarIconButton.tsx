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

import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Tooltip from '@mui/material/Tooltip';
import type React from 'react';
import type { LucideIcon } from 'lucide-react';

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
 *
 * <p>It takes the icon as a component and sizes it itself, and offers no
 * variant: these actions had drifted to different emphases and glyph sizes on
 * the two surfaces, and the fix is that the call site cannot choose either.
 */
export function ToolbarIconButton({
  label,
  icon: Icon,
  onClick,
  busy = false,
  disabled = false,
  expanded,
}: {
  label: string;
  icon: LucideIcon;
  /** Receives the event so a menu trigger can anchor to the button. */
  onClick: (event: React.MouseEvent<HTMLButtonElement>) => void;
  busy?: boolean;
  disabled?: boolean;
  /** Set on a trigger that opens a menu, so assistive tech announces its state. */
  expanded?: boolean;
}) {
  return (
    <Tooltip title={label}>
      <span style={{ display: 'inline-flex', flexShrink: 0 }}>
        <Button
          size="small"
          variant="outlined"
          onClick={onClick}
          disabled={disabled || busy}
          aria-label={label}
          aria-haspopup={expanded === undefined ? undefined : 'menu'}
          aria-expanded={expanded ? true : undefined}
          sx={{ minWidth: 36, px: 1 }}
        >
          {busy ? <CircularProgress size={14} color="inherit" /> : <Icon size={16} />}
        </Button>
      </span>
    </Tooltip>
  );
}
