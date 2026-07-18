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
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Cpu } from 'lucide-react';

/**
 * A distinct emblem for the audit trail's system actor — an automated action
 * with no human behind it (the extraction pipeline, scheduled jobs). Made
 * deliberately UNLIKE a person avatar so a glance separates machine from human:
 * a rounded SQUARE (people are round), a brand blue→navy gradient with a faint
 * scanline texture and an inset top highlight, and a crisp CPU glyph. It reads
 * at once as "the machine did this".
 */
export function SystemAvatar({ size = 24 }: { size?: number }) {
  const theme = useTheme();
  const { blue, blueDeep, navy } = theme.qnop.brand;
  return (
    <Box
      aria-hidden
      sx={{
        position: 'relative',
        width: size,
        height: size,
        flexShrink: 0,
        borderRadius: `${Math.max(4, Math.round(size * 0.28))}px`,
        display: 'grid',
        placeItems: 'center',
        color: '#fff',
        background: `linear-gradient(140deg, ${blue} 0%, ${blueDeep} 48%, ${navy} 100%)`,
        border: `1px solid ${alpha(blue, 0.55)}`,
        boxShadow: `inset 0 1px 0 ${alpha('#ffffff', 0.28)}, 0 1px 2px ${alpha(navy, 0.5)}`,
        overflow: 'hidden',
      }}
    >
      {/* Faint horizontal scanlines — a machine texture, never a face. */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: 0,
          opacity: 0.16,
          backgroundImage: `repeating-linear-gradient(0deg, transparent 0 2px, ${alpha(
            '#ffffff',
            0.7,
          )} 2px 3px)`,
        }}
      />
      <Cpu size={Math.round(size * 0.56)} strokeWidth={2.25} style={{ position: 'relative' }} />
    </Box>
  );
}

/**
 * The system actor as it reads in a table cell: the emblem plus a "System"
 * label, explained on hover. Inert by design — there is no person to visit.
 */
export function SystemActor() {
  return (
    <Tooltip title="Automated system action — no human actor">
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
        <SystemAvatar size={24} />
        <Typography
          component="span"
          sx={{
            fontWeight: 700,
            fontSize: '0.85rem',
            color: 'text.secondary',
            letterSpacing: '0.01em',
          }}
        >
          System
        </Typography>
      </Stack>
    </Tooltip>
  );
}
