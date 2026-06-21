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
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useAuthStore } from '../stores/authStore';
import { ThemeShowcase } from '../components/ThemeShowcase';

/**
 * Placeholder authenticated landing page (#100). It confirms the wiring works —
 * the profile shown here comes from GET /users/me via the auth store — and, for
 * #101, hosts a small theme showcase so the devtank42 design system can be seen
 * and visually verified in both light and dark. The real dashboard arrives with
 * the app shell and review surfaces (#102+); the showcase goes with it.
 */
export function HomePage() {
  const displayName = useAuthStore((s) => s.displayName);
  const email = useAuthStore((s) => s.email);
  const role = useAuthStore((s) => s.role);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Willkommen{displayName ? `, ${displayName}` : ''}.</Typography>
        <Typography color="text.secondary" sx={{ mt: 1 }}>
          {email} · Rolle: {role}
        </Typography>
      </Box>

      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Review-Arbeitsbereich
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>
            Das Dashboard und der Review-Arbeitsbereich entstehen in den nächsten Schritten.
          </Typography>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
            <Button variant="contained">Neuer Review</Button>
            <Button variant="outlined">Vergleichen</Button>
            <Chip label="In Prüfung" color="primary" size="small" variant="outlined" />
          </Stack>
        </CardContent>
      </Card>

      <ThemeShowcase />
    </Stack>
  );
}
