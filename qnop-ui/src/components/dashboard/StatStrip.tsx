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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';

export interface StatTile {
  label: string;
  value: number;
  icon: LucideIcon;
  /** Colours the tile once it deserves attention (e.g. anything overdue). */
  tone?: 'accent' | 'warning' | 'danger' | 'success';
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
        : tone === 'success'
          ? theme.palette.success.main
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
      {tiles.map((tile) => {
        const active = tile.tone && tile.value > 0;
        const color = toneColor(tile.tone);
        const Icon = tile.icon;
        return (
          <Paper
            key={tile.label}
            variant="outlined"
            sx={{
              px: 2,
              py: 1.5,
              borderRadius: '12px',
              bgcolor: active ? alpha(color, 0.05) : 'background.paper',
              borderColor: active ? alpha(color, 0.35) : 'divider',
            }}
          >
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              {/* A playful tinted round — the tile's mood at a glance. */}
              <Box
                aria-hidden
                sx={{
                  width: 34,
                  height: 34,
                  borderRadius: '10px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                  color: active ? color : 'text.secondary',
                  bgcolor: alpha(active ? color : theme.palette.text.secondary, 0.1),
                }}
              >
                <Icon size={16} />
              </Box>
              <Box sx={{ minWidth: 0 }}>
                <Typography
                  sx={{
                    fontSize: '1.4rem',
                    fontWeight: 800,
                    lineHeight: 1.2,
                    fontVariantNumeric: 'tabular-nums',
                    color: tile.value > 0 ? color : 'text.primary',
                  }}
                >
                  {tile.value}
                </Typography>
                <Typography variant="caption" color="text.secondary" noWrap component="p">
                  {tile.label}
                </Typography>
              </Box>
            </Stack>
          </Paper>
        );
      })}
    </Box>
  );
}
