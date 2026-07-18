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

function crestInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * A team's identity crest: a rounded emblem with the team's initials over a
 * deterministic colour from the brand avatar palette (issue #470), so every team
 * reads as its own "guild" at a glance. A diagonal sheen and a colour-matched
 * glow give it depth against the qnop surfaces without leaving the palette.
 */
export function TeamCrest({ name, size = 44 }: { name: string; size?: number }) {
  const theme = useTheme();
  const palette = theme.qnop.avatarPalette;
  const safe = name.trim() || '?';
  const color = palette[(safe.charCodeAt(0) + safe.charCodeAt(safe.length - 1)) % palette.length];
  return (
    <Box
      aria-hidden
      sx={{
        width: size,
        height: size,
        flexShrink: 0,
        borderRadius: `${Math.round(size * 0.3)}px`,
        display: 'grid',
        placeItems: 'center',
        color: '#fff',
        fontWeight: 800,
        fontSize: Math.round(size * 0.36),
        letterSpacing: '0.01em',
        lineHeight: 1,
        bgcolor: color,
        backgroundImage: 'linear-gradient(140deg, rgba(255,255,255,0.22), rgba(0,0,0,0.14) 70%)',
        boxShadow: `0 4px 14px ${color}40`,
      }}
    >
      {crestInitials(safe)}
    </Box>
  );
}
