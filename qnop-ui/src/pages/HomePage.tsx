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
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Inbox } from 'lucide-react';
import { useAuthStore } from '../stores/authStore';

/**
 * Dashboard placeholder (#102). Shows the signed-in user (from GET /users/me)
 * and frames the review workspace to come. The real command-centre dashboard
 * from the prototype lands in the PDF vertical slice (Phase B).
 */
export function HomePage() {
  const displayName = useAuthStore((s) => s.displayName);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Welcome{displayName ? `, ${displayName}` : ''}.</Typography>
        <Typography color="text.secondary" sx={{ mt: 1 }}>
          Your review workspace will take shape here over the next steps.
        </Typography>
      </Box>

      <Card>
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
            <Box
              sx={{
                width: 44,
                height: 44,
                borderRadius: 1.75,
                bgcolor: 'primary.light',
                color: 'primary.main',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
              }}
            >
              <Inbox size={22} />
            </Box>
            <Box>
              <Typography variant="h6">No reviews yet</Typography>
              <Typography color="text.secondary">
                Once document upload is ready, your reviews will appear here.
              </Typography>
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
