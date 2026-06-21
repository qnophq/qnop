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

import { useEffect, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Link from '@mui/material/Link';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { verifyEmail } from '../../api/auth';

type Status = 'verifying' | 'success' | 'error';

/** Landing page for the email-verification link; verifies the token on mount. */
export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [status, setStatus] = useState<Status>(token ? 'verifying' : 'error');
  const ran = useRef(false);

  useEffect(() => {
    if (!token || ran.current) {
      return;
    }
    ran.current = true;
    verifyEmail(token)
      .then(() => setStatus('success'))
      .catch(() => setStatus('error'));
  }, [token]);

  return (
    <AuthLayout title="E-Mail bestätigen">
      {status === 'verifying' && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, color: 'text.secondary' }}>
          <CircularProgress size={20} />
          Adresse wird bestätigt…
        </Box>
      )}
      {status === 'success' && (
        <>
          <Alert severity="success" sx={{ mb: 2 }}>
            Deine E-Mail-Adresse ist bestätigt. Du kannst dich jetzt anmelden.
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            Zur Anmeldung
          </Link>
        </>
      )}
      {status === 'error' && (
        <>
          <Alert severity="error" sx={{ mb: 2 }}>
            Der Bestätigungslink ist ungültig oder abgelaufen.
          </Alert>
          <Link component={RouterLink} to="/login" underline="hover">
            Zur Anmeldung
          </Link>
        </>
      )}
    </AuthLayout>
  );
}
