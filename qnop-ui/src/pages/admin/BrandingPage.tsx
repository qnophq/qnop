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
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { BrandingSlotCard } from '../../components/admin/branding/BrandingSlotCard';

type Toast = { message: string; severity: 'success' | 'error' } | null;

/** Admin branding: upload/replace/remove the light & dark logos and the logomark (#106). */
export function BrandingPage() {
  const [toast, setToast] = useState<Toast>(null);
  const notify = (message: string, severity: 'success' | 'error') =>
    setToast({ message, severity });

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1" sx={{ fontSize: 28 }}>
          Branding
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Upload the logos shown across the app. PNG, WebP or SVG, up to 512 KiB.
        </Typography>
      </Box>

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ alignItems: 'stretch' }}>
        <BrandingSlotCard
          slot="logo-light"
          label="Logo (light)"
          description="Shown on light backgrounds."
          onNotify={notify}
        />
        <BrandingSlotCard
          slot="logo-dark"
          label="Logo (dark)"
          description="Shown on dark backgrounds."
          dark
          onNotify={notify}
        />
        <BrandingSlotCard
          slot="logomark"
          label="Logomark"
          description="Compact mark / favicon."
          onNotify={notify}
        />
      </Stack>

      <Snackbar
        open={toast !== null}
        autoHideDuration={5000}
        onClose={() => setToast(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {toast ? (
          <Alert severity={toast.severity} onClose={() => setToast(null)} variant="filled">
            {toast.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </Stack>
  );
}
