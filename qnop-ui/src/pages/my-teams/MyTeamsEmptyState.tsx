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

import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { keyframes } from '@mui/material/styles';
import { Handshake, Trophy, UserPlus, Users } from 'lucide-react';
import { JoinTeamIllustration } from '../../components/my-teams/JoinTeamIllustration';

const fadeUp = keyframes`
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
`;

/** A staggered fade-up, disabled under reduced-motion. */
function rise(delayMs: number) {
  return {
    animation: `${fadeUp} 520ms cubic-bezier(0.16, 1, 0.3, 1) both`,
    animationDelay: `${delayMs}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  } as const;
}

const PERKS: { icon: ReactNode; label: string }[] = [
  { icon: <Users size={15} />, label: 'Collaborate on reviews' },
  { icon: <Handshake size={15} />, label: 'Share the workload' },
  { icon: <Trophy size={15} />, label: 'Rise through the ranks' },
];

/**
 * The "you're on no team" state of My Teams (issue #470), reframed as an
 * invitation rather than a dead end: an animated open-seat illustration, an
 * encouraging headline, and the one thing the viewer needs to know — a lead or an
 * admin adds them. Styled full-width in the qnop system with a quiet brand glow.
 */
export function MyTeamsEmptyState() {
  return (
    <Paper
      variant="outlined"
      sx={{
        position: 'relative',
        overflow: 'hidden',
        px: { xs: 3, sm: 5 },
        py: { xs: 5, sm: 7 },
        textAlign: 'center',
        backgroundImage:
          'radial-gradient(90% 120% at 50% -10%, rgba(18,144,239,0.12), transparent 62%)',
      }}
    >
      <Stack sx={{ alignItems: 'center', maxWidth: 560, mx: 'auto' }}>
        <Box sx={{ width: '100%', maxWidth: 340, mb: 1 }}>
          <JoinTeamIllustration />
        </Box>

        <Typography
          sx={{
            ...rise(120),
            fontSize: 11.5,
            fontWeight: 700,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Teams
        </Typography>

        <Typography
          component="h2"
          sx={{
            ...rise(180),
            fontSize: { xs: 24, sm: 28 },
            fontWeight: 800,
            mt: 0.75,
            textWrap: 'balance',
          }}
        >
          Your seat is waiting
        </Typography>

        <Typography
          sx={{ ...rise(240), color: 'text.secondary', fontSize: 15, lineHeight: 1.6, mt: 1.25 }}
        >
          Teams are how reviewers band together — share the workload, keep each other in the loop,
          and climb the ranks. You’re not on one yet.
        </Typography>

        <Stack
          direction="row"
          spacing={1}
          sx={{ ...rise(320), flexWrap: 'wrap', justifyContent: 'center', gap: 1, mt: 2.5 }}
        >
          {PERKS.map((perk) => (
            <Box
              key={perk.label}
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                px: 1.5,
                py: 0.75,
                borderRadius: 999,
                fontSize: 13,
                fontWeight: 600,
                color: 'text.secondary',
                bgcolor: (t) => t.qnop.surface2,
                border: 1,
                borderColor: 'divider',
                '& svg': { color: 'primary.main' },
              }}
            >
              {perk.icon}
              {perk.label}
            </Box>
          ))}
        </Stack>

        <Stack
          direction="row"
          spacing={1}
          sx={{
            ...rise(400),
            alignItems: 'center',
            justifyContent: 'center',
            mt: 3.5,
            px: 2,
            py: 1.25,
            borderRadius: 2,
            bgcolor: 'primary.light',
            color: 'primary.main',
            maxWidth: 'fit-content',
            mx: 'auto',
          }}
        >
          <UserPlus size={16} />
          <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
            A team lead or an administrator can add you — your teams appear here the moment they do.
          </Typography>
        </Stack>
      </Stack>
    </Paper>
  );
}
