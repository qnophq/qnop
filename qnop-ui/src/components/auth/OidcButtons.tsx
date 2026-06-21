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

import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import { LogIn } from 'lucide-react';
import { useConfig } from '../../api/hooks/useConfig';

/**
 * "Login with <provider>" buttons for each enabled OIDC provider (from
 * /config). OIDC is a full-page browser handshake, so the button navigates the
 * window to the provider's login URL rather than making an XHR. SAML is not
 * supported (backend is OIDC-only). Renders nothing when no provider is enabled.
 */
export function OidcButtons() {
  const { data } = useConfig();
  const providers = data?.auth?.oidcProviders ?? [];

  if (providers.length === 0) {
    return null;
  }

  return (
    <>
      <Divider sx={{ my: 2.5, color: 'text.disabled', fontSize: 12 }}>or</Divider>
      <Stack spacing={1.25}>
        {providers.map((p) => (
          <Button
            key={p.id}
            variant="outlined"
            color="inherit"
            fullWidth
            startIcon={<LogIn size={16} />}
            onClick={() => {
              window.location.href = p.loginUrl;
            }}
            sx={{ borderColor: 'divider', color: 'text.primary', justifyContent: 'center' }}
          >
            Sign in with {p.name}
          </Button>
        ))}
      </Stack>
    </>
  );
}
