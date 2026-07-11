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
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Crosshair, X } from 'lucide-react';

const EXCERPT_MAX = 60;

interface ReattachHintBarProps {
  /** The lost placement's old quote — null for region-only annotations. */
  excerpt: string | null;
  onCancel: () => void;
}

/**
 * The "placing" mode indicator (issue #457): while a lost annotation is armed
 * for re-attaching, this pill floats over the document stage and turns the
 * next text/region selection into the new anchor. Escape (or the X) leaves
 * the mode without touching anything.
 */
export function ReattachHintBar({ excerpt, onCancel }: ReattachHintBarProps) {
  const theme = useTheme();
  const trimmed =
    excerpt && excerpt.length > EXCERPT_MAX ? `${excerpt.slice(0, EXCERPT_MAX)}…` : excerpt;

  return (
    <Stack
      direction="row"
      spacing={1}
      data-testid="reattach-hint"
      sx={{
        position: 'absolute',
        top: 12,
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 5,
        maxWidth: 'calc(100% - 32px)',
        alignItems: 'center',
        pl: 1.5,
        pr: 0.5,
        py: 0.5,
        borderRadius: '999px',
        border: '1px solid',
        borderColor: alpha(theme.palette.info.main, 0.5),
        bgcolor: 'background.paper',
        boxShadow:
          theme.qnop.mode === 'dark'
            ? `0 4px 24px ${alpha('#000', 0.5)}`
            : '0 4px 24px rgba(1,32,66,0.18)',
      }}
    >
      <Box
        aria-hidden
        sx={{
          color: theme.palette.info.main,
          display: 'flex',
          flexShrink: 0,
          '@keyframes reattachPulse': {
            '0%, 100%': { opacity: 1 },
            '50%': { opacity: 0.35 },
          },
          animation: 'reattachPulse 1.6s ease-in-out infinite',
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}
      >
        <Crosshair size={15} />
      </Box>
      <Typography variant="body2" noWrap sx={{ minWidth: 0 }}>
        {trimmed ? (
          <>
            Placing{' '}
            <Box component="span" sx={{ fontWeight: 600 }}>
              “{trimmed}”
            </Box>{' '}
            — select the new passage
          </>
        ) : (
          'Placing the annotation — select its new location'
        )}
      </Typography>
      <Box
        component="kbd"
        sx={{
          flexShrink: 0,
          px: 0.75,
          py: 0.1,
          borderRadius: '5px',
          border: '1px solid',
          borderColor: 'divider',
          fontFamily: 'inherit',
          fontSize: 11,
          color: 'text.secondary',
        }}
      >
        Esc
      </Box>
      <IconButton size="small" aria-label="Cancel re-attaching" onClick={onCancel}>
        <X size={13} />
      </IconButton>
    </Stack>
  );
}
