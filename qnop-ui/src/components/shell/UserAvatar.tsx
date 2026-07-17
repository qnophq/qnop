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

import { useState } from 'react';
import Box from '@mui/material/Box';
import { useTheme } from '@mui/material/styles';

interface UserAvatarProps {
  name: string | null;
  size?: number;
  /** Uploaded profile picture; falls back to the initials avatar when absent or it fails to load. */
  imageUrl?: string | null;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Avatar: renders the uploaded profile picture when present, otherwise an initials
 * avatar with a deterministic colour from the brand palette (issue #117). A broken
 * or expired image URL falls back to the initials too.
 */
export function UserAvatar({ name, size = 28, imageUrl }: UserAvatarProps) {
  const theme = useTheme();
  const safe = name ?? '?';
  const palette = theme.qnop.avatarPalette;
  const idx = (safe.charCodeAt(0) + safe.charCodeAt(safe.length - 1)) % palette.length;

  // Track load failure so a broken/expired image falls back to initials. Reset it during render
  // when the URL changes (e.g. the cache-busting ?v= after an upload) — the React-recommended
  // "adjust state during render" pattern, avoiding a setState-in-effect cascade.
  const [failed, setFailed] = useState(false);
  const [lastUrl, setLastUrl] = useState(imageUrl);
  if (imageUrl !== lastUrl) {
    setLastUrl(imageUrl);
    setFailed(false);
  }
  const showImage = Boolean(imageUrl) && !failed;

  return (
    <Box
      aria-hidden
      sx={{
        width: size,
        height: size,
        borderRadius: '50%',
        flexShrink: 0,
        overflow: 'hidden',
        bgcolor: palette[idx],
        // On the black-based dark surfaces (issue #423) the darkest palette
        // tones sink below 3:1 against the page — a quiet ring keeps every
        // avatar's boundary readable without touching the identity colours.
        border: theme.qnop.mode === 'dark' ? `1px solid ${theme.palette.divider}` : 'none',
        color: '#fff',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: Math.round(size * 0.4),
        fontWeight: 600,
        lineHeight: 1,
      }}
    >
      {showImage ? (
        <Box
          component="img"
          src={imageUrl ?? undefined}
          alt=""
          onError={() => setFailed(true)}
          sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
        />
      ) : (
        initials(safe)
      )}
    </Box>
  );
}
