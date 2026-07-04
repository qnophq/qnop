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
import Drawer from '@mui/material/Drawer';
import type { ReactNode } from 'react';

interface FocusDrawerProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}

/**
 * Focus mode's temporary home of the annotation list (issue #291): the full
 * panel — filters, sections, the orphaned group — slides in on demand and
 * disappears again, keeping the document at full width the rest of the time.
 */
export function FocusDrawer({ open, onClose, children }: FocusDrawerProps) {
  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{ paper: { sx: { width: { xs: '100%', sm: 420 } } } }}
    >
      <Box sx={{ p: 2, overflowY: 'auto' }}>{children}</Box>
    </Drawer>
  );
}
