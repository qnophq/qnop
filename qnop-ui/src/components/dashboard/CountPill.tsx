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

/**
 * The card header's total (issue #454 follow-up): with the lists capped to a
 * scrolling viewport, the pill keeps the real volume honest at a glance.
 * Renders nothing for zero — an empty card already says so.
 */
export function CountPill({ value }: { value: number }) {
  const theme = useTheme();
  if (value === 0) return null;
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        minWidth: 24,
        height: 22,
        px: 0.75,
        borderRadius: '11px',
        fontSize: '0.75rem',
        fontWeight: 700,
        fontVariantNumeric: 'tabular-nums',
        color: theme.qnop.brand.blue,
        bgcolor: alpha(theme.qnop.brand.blue, 0.1),
      }}
    >
      {value}
    </Box>
  );
}
