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
import type { LucideIcon } from 'lucide-react';
import { BellRing, Rocket, Smile, Trophy, Users } from 'lucide-react';
import type { Achievement } from './profileModel';

const BADGE_ICONS: Record<string, LucideIcon> = {
  liftoff: Rocket,
  crew: Users,
  closer: Trophy,
  face: Smile,
  'tuned-in': BellRing,
};

const BADGE_TONE: Record<string, 'blue' | 'warning' | 'success'> = {
  liftoff: 'blue',
  crew: 'success',
  closer: 'warning',
  face: 'blue',
  'tuned-in': 'success',
};

/**
 * The player card's achievement stickers (issue #469) — the launch pad's
 * sticker language, earned from real state: earned badges glow in their tone,
 * locked ones wait as dashed silhouettes whose tooltip says how to get them.
 */
export function AchievementRow({ achievements }: { achievements: Achievement[] }) {
  const theme = useTheme();
  const dark = theme.qnop.mode === 'dark';
  const toneColor = (tone: 'blue' | 'warning' | 'success') =>
    tone === 'blue'
      ? theme.qnop.brand.blue
      : tone === 'warning'
        ? theme.palette.warning.main
        : theme.palette.success.main;

  return (
    <Stack direction="row" spacing={1.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
      {achievements.map((achievement) => {
        const Icon = BADGE_ICONS[achievement.key] ?? Rocket;
        const color = toneColor(BADGE_TONE[achievement.key] ?? 'blue');
        return (
          <Tooltip
            key={achievement.key}
            title={`${achievement.title} — ${achievement.caption}`}
            placement="top"
          >
            <Stack
              spacing={0.5}
              tabIndex={0}
              aria-label={`${achievement.title}: ${achievement.caption}${achievement.earned ? '' : ' (locked)'}`}
              sx={{
                alignItems: 'center',
                width: 74,
                borderRadius: '12px',
                outline: 'none',
                '&:focus-visible': { boxShadow: theme.qnop.focusRing },
              }}
            >
              <Box
                aria-hidden
                sx={{
                  width: 46,
                  height: 46,
                  borderRadius: '14px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: achievement.earned ? color : 'text.disabled',
                  bgcolor: achievement.earned
                    ? alpha(color, dark ? 0.18 : 0.12)
                    : alpha(theme.palette.text.secondary, 0.06),
                  border: achievement.earned
                    ? `1px solid ${alpha(color, 0.35)}`
                    : `1.5px dashed ${alpha(theme.palette.text.secondary, 0.35)}`,
                  transition: 'transform 150ms ease',
                  '@media (hover: hover)': { '&:hover': { transform: 'translateY(-2px)' } },
                }}
              >
                <Icon size={20} />
              </Box>
              <Typography
                variant="caption"
                noWrap
                sx={{
                  maxWidth: '100%',
                  fontWeight: achievement.earned ? 600 : 400,
                  color: achievement.earned ? 'text.primary' : 'text.disabled',
                }}
              >
                {achievement.title}
              </Typography>
            </Stack>
          </Tooltip>
        );
      })}
    </Stack>
  );
}
