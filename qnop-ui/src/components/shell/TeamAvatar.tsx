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
import { UsersRound } from 'lucide-react';

interface TeamAvatarProps {
  name: string | null;
  size?: number;
  /** Uploaded team picture (issue #509); falls back to the initials crest when absent or it fails. */
  imageUrl?: string | null;
}

function crestInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * A team's identity avatar (issue #509): the uploaded team picture when set, else the
 * initials crest — a rounded emblem with a deterministic colour from the brand palette
 * (the #470 "guild" treatment), so a team without a picture still reads as its own. The
 * shape stays a rounded square (vs. the circular {@link UserAvatar}), and a small
 * people-glyph marker rides the bottom-right corner — with an uploaded photo the shape
 * alone gets subtle, so every team avatar says "this is a group" at a glance. A
 * broken/expired image URL falls back to the crest.
 */
export function TeamAvatar({ name, size = 44, imageUrl }: TeamAvatarProps) {
  const theme = useTheme();
  const palette = theme.qnop.avatarPalette;
  const safe = name?.trim() || '?';
  const color = palette[(safe.charCodeAt(0) + safe.charCodeAt(safe.length - 1)) % palette.length];
  const radius = `${Math.round(size * 0.3)}px`;

  // The marker scales with the avatar but never below legibility; it hangs
  // slightly over the corner so it reads as a badge, not a sticker.
  const badgeSize = Math.max(12, Math.round(size * 0.42));
  const badgeOverhang = -Math.round(badgeSize * 0.18);

  // Reset the load-failure flag when the URL changes (cache-busting ?v= after an upload);
  // the "adjust state during render" pattern, matching UserAvatar.
  const [failed, setFailed] = useState(false);
  const [lastUrl, setLastUrl] = useState(imageUrl);
  if (imageUrl !== lastUrl) {
    setLastUrl(imageUrl);
    setFailed(false);
  }
  const showImage = Boolean(imageUrl) && !failed;

  return (
    <Box aria-hidden sx={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
      <Box
        sx={{
          width: '100%',
          height: '100%',
          borderRadius: radius,
          overflow: 'hidden',
          display: 'grid',
          placeItems: 'center',
          color: '#fff',
          fontWeight: 800,
          fontSize: Math.round(size * 0.36),
          letterSpacing: '0.01em',
          lineHeight: 1,
          bgcolor: color,
          backgroundImage: showImage
            ? 'none'
            : 'linear-gradient(140deg, rgba(255,255,255,0.22), rgba(0,0,0,0.14) 70%)',
          boxShadow: showImage ? 'none' : `0 4px 14px ${color}40`,
          border: theme.qnop.mode === 'dark' ? `1px solid ${theme.palette.divider}` : 'none',
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
          crestInitials(safe)
        )}
      </Box>
      {/* The "this is a group" marker (#509 follow-up): a paper-backed ring so
          it separates from any underlying photo, in both themes. */}
      <Box
        data-testid="team-avatar-marker"
        sx={{
          position: 'absolute',
          right: badgeOverhang,
          bottom: badgeOverhang,
          width: badgeSize,
          height: badgeSize,
          borderRadius: '50%',
          bgcolor: 'background.paper',
          border: '1px solid',
          borderColor: 'divider',
          display: 'grid',
          placeItems: 'center',
          color: 'text.secondary',
        }}
      >
        <UsersRound size={Math.max(7, Math.round(badgeSize * 0.58))} />
      </Box>
    </Box>
  );
}
