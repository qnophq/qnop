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
import Drawer from '@mui/material/Drawer';
import { ResizeHandle } from './ResizeHandle';
import { useResizeHandle } from './useResizeHandle';

/** Never narrower than a thread needs to breathe, never the whole viewport. */
const DEFAULT_MIN_WIDTH = 380;

interface ResizableDrawerProps {
  open: boolean;
  onClose: () => void;
  /** localStorage key for the persisted width — one preference per surface. */
  storageKey: string;
  defaultWidth: number;
  minWidth?: number;
  /** Accessible name of the resize separator. */
  handleAriaLabel: string;
  handleTestId?: string;
  drawerTestId?: string;
  children: ReactNode;
}

/**
 * A right-anchored drawer whose width is a personal working preference
 * (issue #403), resized and persisted through {@link useResizeHandle}. One
 * mechanism for every review drawer — focus-mode panel and task details
 * resize and remember identically.
 */
export function ResizableDrawer({
  open,
  onClose,
  storageKey,
  defaultWidth,
  minWidth = DEFAULT_MIN_WIDTH,
  handleAriaLabel,
  handleTestId,
  drawerTestId,
  children,
}: ResizableDrawerProps) {
  const resize = useResizeHandle({ storageKey, defaultWidth, minWidth });

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{
        paper: { sx: { width: { xs: '100%', sm: resize.width }, overflow: 'visible' } },
      }}
      data-testid={drawerTestId}
    >
      <ResizeHandle
        ariaLabel={handleAriaLabel}
        testId={handleTestId}
        minWidth={minWidth}
        state={resize}
      />
      {children}
    </Drawer>
  );
}
