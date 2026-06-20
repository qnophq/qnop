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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';

interface ErrorStateProps {
  code: string;
  title: string;
  message: string;
}

/** Shared, centered empty/error state used by the error pages. */
export function ErrorState({ code, title, message }: ErrorStateProps) {
  return (
    <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '60dvh', p: 2 }}>
      <Stack spacing={1} sx={{ alignItems: 'center', textAlign: 'center' }}>
        <Typography variant="h1" sx={{ fontSize: '3rem', color: 'text.secondary' }}>
          {code}
        </Typography>
        <Typography variant="h6" component="h1">
          {title}
        </Typography>
        <Typography color="text.secondary">{message}</Typography>
        <Button component={RouterLink} to="/" sx={{ mt: 1 }}>
          Zur Startseite
        </Button>
      </Stack>
    </Box>
  );
}
