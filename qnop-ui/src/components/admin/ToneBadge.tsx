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
import { useTheme } from '@mui/material/styles';

export type BadgeTone = 'blue' | 'green' | 'amber' | 'red' | 'neutral';

/** A compact, system-coloured pill driven by the shared `qnop.badge` tones (#104/#105). */
export function ToneBadge({ tone, label }: { tone: BadgeTone; label: string }) {
  const theme = useTheme();
  const t =
    tone === 'neutral'
      ? {
          bg: theme.qnop.surface2,
          fg: theme.palette.text.secondary,
          border: theme.palette.divider,
        }
      : theme.qnop.badge[tone];
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        px: 1,
        py: 0.25,
        borderRadius: 1,
        fontSize: 12,
        fontWeight: 600,
        lineHeight: 1.6,
        whiteSpace: 'nowrap',
        bgcolor: t.bg,
        color: t.fg,
        border: '1px solid',
        borderColor: t.border,
      }}
    >
      {label}
    </Box>
  );
}
