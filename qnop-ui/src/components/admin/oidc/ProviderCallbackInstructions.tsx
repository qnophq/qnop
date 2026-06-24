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

import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { Check, Copy, ExternalLink } from 'lucide-react';
import type { OidcProviderTypeDto } from '../../../api/generated';

interface ProviderHelp {
  /** Where in the provider's admin UI the operator finds the redirect-URI setting. */
  whereToFind: string;
  /** What that field is labelled at the upstream provider. */
  fieldLabel: string;
  /** Optional direct link to the provider's console / developer settings. */
  consoleUrl?: string;
  /** Human-readable label for the console link. */
  consoleLabel?: string;
}

/**
 * Per-provider hints for *where* the callback URL needs to be pasted at the
 * upstream IdP. For OIDC/OAUTH2 we stay generic — those types cover any
 * conformant provider, so naming a specific console would mislead.
 */
const PROVIDER_HELP: Record<OidcProviderTypeDto, ProviderHelp> = {
  GOOGLE: {
    whereToFind: 'APIs & Services → Credentials → OAuth 2.0 Client IDs → your client',
    fieldLabel: 'Authorized redirect URIs',
    consoleUrl: 'https://console.cloud.google.com/apis/credentials',
    consoleLabel: 'Open Google Cloud Console',
  },
  GITHUB: {
    whereToFind: 'GitHub → Settings → Developer settings → OAuth Apps → your app',
    fieldLabel: 'Authorization callback URL',
    consoleUrl: 'https://github.com/settings/developers',
    consoleLabel: 'Open GitHub developer settings',
  },
  FACEBOOK: {
    whereToFind: 'Meta for Developers → your app → Facebook Login → Settings',
    fieldLabel: 'Valid OAuth Redirect URIs',
    consoleUrl: 'https://developers.facebook.com/apps',
    consoleLabel: 'Open Meta for Developers',
  },
  OIDC: {
    whereToFind: "Your IdP's client configuration (Keycloak, Authentik, Auth0, Dex, …)",
    fieldLabel: 'Valid Redirect URIs',
  },
  OAUTH2: {
    whereToFind: "Your provider's OAuth2 application settings",
    fieldLabel: 'Redirect URI / Callback URL',
  },
};

/**
 * The OAuth2 callback URL qnop listens on for a provider — mirrors Spring's
 * `{origin}/login/oauth2/code/{registrationId}` with the registration id being
 * the provider's UUID. `window.location.origin` is what the browser hits when
 * the upstream redirects back.
 */
function buildCallbackUrl(providerId: string): string {
  return `${window.location.origin}/login/oauth2/code/${providerId}`;
}

interface ProviderCallbackInstructionsProps {
  providerId: string;
  providerType: OidcProviderTypeDto;
  /** `success` = fresh-create hero step; `inline` = neutral edit-mode reference card. */
  variant?: 'success' | 'inline';
}

/**
 * "Register this URL with your IdP" panel: the callback URL in monospace with a
 * one-click copy, plus the right setting to paste it into on the provider's
 * side. This is the one external value an operator must act on after creating a
 * provider, so it is composed as the hero of its container, not a footnote.
 */
export function ProviderCallbackInstructions({
  providerId,
  providerType,
  variant = 'inline',
}: ProviderCallbackInstructionsProps) {
  const callbackUrl = useMemo(() => buildCallbackUrl(providerId), [providerId]);
  const help = PROVIDER_HELP[providerType];
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(callbackUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard can fail in non-secure contexts; the URL stays selectable for manual copy.
    }
  };

  const isSuccess = variant === 'success';

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 1.5,
        p: 2.25,
        borderRadius: 1.5,
        border: 1,
        borderColor: isSuccess ? 'success.light' : 'divider',
        bgcolor: isSuccess ? 'rgba(46, 125, 50, 0.06)' : 'rgba(0, 0, 0, 0.02)',
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
        <Typography
          variant="overline"
          sx={{
            color: isSuccess ? 'success.main' : 'text.secondary',
            letterSpacing: 0.8,
            fontWeight: 600,
            lineHeight: 1,
          }}
        >
          Callback URL
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          register this at your provider
        </Typography>
      </Stack>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'stretch' }}>
        <Box
          component="code"
          tabIndex={0}
          aria-label="OAuth2 callback URL"
          sx={{
            flex: 1,
            minWidth: 0,
            px: 1.5,
            py: 1.25,
            borderRadius: 1,
            border: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            fontFamily: '"JetBrains Mono", "SF Mono", Menlo, ui-monospace, monospace',
            fontSize: '0.85rem',
            color: 'text.primary',
            wordBreak: 'break-all',
            userSelect: 'all',
          }}
        >
          {callbackUrl}
        </Box>
        <Tooltip title={copied ? 'Copied' : 'Copy to clipboard'} placement="top" arrow>
          <IconButton
            onClick={handleCopy}
            aria-label="Copy callback URL"
            color={copied ? 'success' : 'default'}
            sx={{
              border: 1,
              borderColor: copied ? 'success.light' : 'divider',
              borderRadius: 1,
              alignSelf: 'stretch',
              px: 1.5,
            }}
          >
            {copied ? <Check size={18} /> : <Copy size={18} />}
          </IconButton>
        </Tooltip>
      </Stack>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.25}
        sx={{
          alignItems: { xs: 'flex-start', sm: 'center' },
          justifyContent: 'space-between',
          mt: 0.25,
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ lineHeight: 1.45 }}>
            <Typography component="span" variant="body2" sx={{ color: 'text.secondary' }}>
              Paste into:{' '}
            </Typography>
            <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
              {help.fieldLabel}
            </Typography>
          </Typography>
          <Typography
            variant="caption"
            sx={{ color: 'text.secondary', display: 'block', lineHeight: 1.4, mt: 0.25 }}
          >
            {help.whereToFind}
          </Typography>
        </Box>
        {help.consoleUrl && (
          <Button
            component="a"
            href={help.consoleUrl}
            target="_blank"
            rel="noopener noreferrer"
            size="small"
            variant="text"
            endIcon={<ExternalLink size={14} />}
            sx={{ flexShrink: 0, whiteSpace: 'nowrap' }}
          >
            {help.consoleLabel ?? 'Open provider console'}
          </Button>
        )}
      </Stack>
    </Box>
  );
}
