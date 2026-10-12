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
import Divider from '@mui/material/Divider';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { OidcProviderLoginInfo } from '../../api/generated';
import { useConfig } from '../../api/hooks/useConfig';
import { ProviderIcon } from './ProviderIcon';
import { Trans, useTranslation } from 'react-i18next';

/**
 * "Sign in with <provider>" buttons for each enabled OIDC provider (from
 * /config). OIDC is a full-page browser handshake, so each button is a real
 * anchor — Spring Security intercepts the navigation, and the operator keeps
 * keyboard, middle-click, and open-in-new-tab semantics. Renders nothing when
 * no provider is enabled. SAML is not supported (backend is OIDC-only).
 */
export function OidcButtons() {
  const { t } = useTranslation('auth');
  const { data } = useConfig();
  const providers = data?.auth?.oidcProviders ?? [];

  if (providers.length === 0) {
    return null;
  }

  return (
    <>
      <Divider sx={{ my: 2.5, color: 'text.disabled', fontSize: 12 }}>{t('shared.or')}</Divider>
      <Stack spacing={1.5}>
        {providers.map((provider) => (
          <OidcProviderButton key={provider.id} provider={provider} />
        ))}
      </Stack>
    </>
  );
}

interface OidcProviderButtonProps {
  provider: OidcProviderLoginInfo;
}

/**
 * One provider button plus an optional "Use a different account" affordance
 * underneath. The primary button is silent SSO (re-uses the upstream session);
 * the affordance lets the operator switch accounts where the provider supports
 * it.
 */
function OidcProviderButton({ provider }: OidcProviderButtonProps) {
  const { t } = useTranslation('auth');
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0.5 }}>
      <Button
        component="a"
        href={provider.loginUrl}
        variant="outlined"
        color="inherit"
        fullWidth
        startIcon={<ProviderIcon kind={provider.iconKind} />}
        sx={{ borderColor: 'divider', color: 'text.primary', justifyContent: 'center' }}
      >
        {t('oidc.signInWith', { provider: provider.name })}
      </Button>
      <AccountSwitchAffordance provider={provider} />
    </Box>
  );
}

/**
 * The small caption under a provider button. Renders a clickable "Use a
 * different account" link when the provider honours a `prompt` (OIDC/Google/
 * Facebook/OAuth2), a textual upstream sign-out hint when it does not (GitHub),
 * or nothing when the backend omits both fields (defensive).
 */
function AccountSwitchAffordance({ provider }: OidcProviderButtonProps) {
  const { t } = useTranslation('auth');
  if (provider.accountPickerLoginUrl) {
    return (
      <Link
        component="a"
        href={provider.accountPickerLoginUrl}
        variant="caption"
        underline="hover"
        aria-label={t('oidc.differentAccountAria', { provider: provider.name })}
        sx={{ color: 'text.secondary', fontWeight: 500, '&:hover': { color: 'text.secondary' } }}
      >
        {t('oidc.differentAccount')}
      </Link>
    );
  }
  if (provider.accountSwitchHintUrl) {
    const host = safeHostname(provider.accountSwitchHintUrl);
    return (
      <Typography
        variant="caption"
        sx={{ color: 'text.secondary', textAlign: 'center', lineHeight: 1.4 }}
      >
        <Trans
          t={t}
          i18nKey="oidc.switchHint"
          values={{ host }}
          // Not `link`: that is a void HTML element, which Trans would close before the host.
          components={{
            hostLink: (
              <Link
                component="a"
                href={provider.accountSwitchHintUrl}
                target="_blank"
                rel="noopener noreferrer"
                underline="hover"
                color="inherit"
                sx={{ fontFamily: 'monospace' }}
              />
            ),
          }}
        />
      </Typography>
    );
  }
  return null;
}

/** Hostname of an absolute URL for inline display; returns the input on parse failure. */
function safeHostname(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}
