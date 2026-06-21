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
import { ApplicationSettingsForm } from '../../components/admin/settings/ApplicationSettingsForm';

/**
 * Admin application settings screen (issue #106): general, upload, tracking,
 * SMTP and authentication settings. OIDC providers, mail templates and branding
 * are separate Administration menu items, each with its own screen.
 */
export function SettingsPage() {
  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1" sx={{ fontSize: 28 }}>
          Settings
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Workspace, uploads, usage tracking, email and authentication.
        </Typography>
      </Box>
      <ApplicationSettingsForm />
    </Stack>
  );
}
