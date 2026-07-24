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
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';

/**
 * The shared "designed emptiness" anatomy of the dashboard boxes (issue #588):
 * a tinted icon medallion, a bold one-liner, a quiet explanation. Emptiness is
 * a state with its own meaning per box — green celebrates finished work, blue
 * stays calm or invites the next step, amber marks the pleasant quiet.
 */
export function CardEmptyState({
  icon: Icon,
  title,
  text,
  tone = 'blue',
}: {
  icon: LucideIcon;
  title: string;
  text: string;
  tone?: 'green' | 'blue' | 'amber';
}) {
  const theme = useTheme();
  const color =
    tone === 'green'
      ? theme.palette.success.main
      : tone === 'amber'
        ? theme.palette.warning.main
        : theme.qnop.brand.blue;
  return (
    <Stack spacing={1} sx={{ alignItems: 'center', py: 2, textAlign: 'center' }}>
      <Box
        aria-hidden
        sx={{
          width: 44,
          height: 44,
          borderRadius: '50%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color,
          bgcolor: alpha(color, 0.12),
        }}
      >
        <Icon size={20} />
      </Box>
      <Typography sx={{ fontWeight: 700 }}>{title}</Typography>
      <Typography variant="body2" color="text.secondary">
        {text}
      </Typography>
    </Stack>
  );
}
