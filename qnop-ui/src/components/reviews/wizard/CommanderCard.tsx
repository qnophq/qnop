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
import { ShieldCheck } from 'lucide-react';
import { UserAvatar } from '../../shell/UserAvatar';
import { useAuthStore } from '../../../stores/authStore';

/**
 * The wizard rail's "mission commander" card (issue #469): the review's
 * future owner — the signed-in creator — with their picture, so starting a
 * review feels personal, not procedural. Same campaign language as the
 * player card: soft brand gradient, ringed avatar, one calm role line.
 */
export function CommanderCard() {
  const theme = useTheme();
  const displayName = useAuthStore((s) => s.displayName);
  const email = useAuthStore((s) => s.email);
  const avatarUrl = useAuthStore((s) => s.avatarUrl);
  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2.5,
        borderRadius: '16px',
        background: `
          radial-gradient(70% 130% at 100% 0%, ${alpha(blue, dark ? 0.16 : 0.08)} 0%, transparent 100%),
          ${theme.palette.background.paper}
        `,
      }}
    >
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Box
          sx={{
            borderRadius: '50%',
            p: '3px',
            flexShrink: 0,
            border: `2px solid ${alpha(blue, 0.45)}`,
          }}
        >
          <UserAvatar name={displayName} size={52} imageUrl={avatarUrl} />
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography
            variant="caption"
            sx={{
              display: 'block',
              textTransform: 'uppercase',
              letterSpacing: '0.08em',
              fontSize: 10,
              fontWeight: 700,
              color: blue,
            }}
          >
            Mission commander
          </Typography>
          <Typography sx={{ fontWeight: 700, lineHeight: 1.3 }} noWrap>
            {displayName ?? 'You'}
          </Typography>
          <Typography variant="caption" color="text.secondary" noWrap component="p">
            {email}
          </Typography>
        </Box>
      </Stack>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1.75 }}>
        <Box aria-hidden sx={{ color: blue, display: 'flex', flexShrink: 0 }}>
          <ShieldCheck size={14} />
        </Box>
        <Typography variant="caption" color="text.secondary">
          You own this review — crew, deadline and the final call are yours.
        </Typography>
      </Stack>
    </Paper>
  );
}
