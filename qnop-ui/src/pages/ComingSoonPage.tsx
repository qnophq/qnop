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
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { LucideIcon } from 'lucide-react';

interface ComingSoonPageProps {
  title: string;
  description: string;
  icon: LucideIcon;
}

/**
 * In-brand placeholder for surfaces whose screens land in later issues (Reviews,
 * admin users/teams/settings, compliance). Keeps the shell fully navigable and
 * the role-gating demonstrable until the real pages replace it (#104+).
 */
export function ComingSoonPage({ title, description, icon: Icon }: ComingSoonPageProps) {
  return (
    <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '60dvh', p: 2 }}>
      <Stack spacing={2} sx={{ alignItems: 'center', maxWidth: 460, textAlign: 'center' }}>
        <Box
          sx={{
            width: 64,
            height: 64,
            borderRadius: 2,
            bgcolor: 'primary.light',
            color: 'primary.main',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Icon size={28} />
        </Box>
        <Typography variant="h3" component="h1">
          {title}
        </Typography>
        <Typography color="text.secondary">{description}</Typography>
        <Chip label="In Vorbereitung" color="primary" variant="outlined" size="small" />
      </Stack>
    </Box>
  );
}
