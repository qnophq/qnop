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
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';

export interface StatTile {
  label: string;
  value: number;
  /** Colours the value once it deserves attention (e.g. anything overdue). */
  tone?: 'accent' | 'warning' | 'danger';
}

/**
 * The masthead's glance numbers (issue #454, prototype anatomy): four quiet
 * tiles whose values only take colour when they mean work — tabular numerals,
 * no decoration.
 */
export function StatStrip({ tiles }: { tiles: StatTile[] }) {
  const theme = useTheme();
  const toneColor = (tone?: StatTile['tone']) =>
    tone === 'danger'
      ? theme.palette.error.main
      : tone === 'warning'
        ? theme.palette.warning.main
        : tone === 'accent'
          ? theme.qnop.brand.blue
          : theme.palette.text.primary;

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(4, 1fr)' },
        gap: 1.5,
      }}
    >
      {tiles.map((tile) => (
        <Paper
          key={tile.label}
          variant="outlined"
          sx={{
            px: 2,
            py: 1.5,
            borderRadius: '10px',
            bgcolor:
              tile.tone && tile.value > 0 ? alpha(toneColor(tile.tone), 0.05) : 'background.paper',
          }}
        >
          <Typography
            sx={{
              fontSize: '1.5rem',
              fontWeight: 800,
              lineHeight: 1.2,
              fontVariantNumeric: 'tabular-nums',
              color: tile.value > 0 ? toneColor(tile.tone) : 'text.primary',
            }}
          >
            {tile.value}
          </Typography>
          <Typography variant="caption" color="text.secondary" noWrap component="p">
            {tile.label}
          </Typography>
        </Paper>
      ))}
    </Box>
  );
}
