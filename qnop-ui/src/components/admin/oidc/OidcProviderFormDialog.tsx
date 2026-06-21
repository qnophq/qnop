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

import { useState, type FormEvent } from 'react';
import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import InputAdornment from '@mui/material/InputAdornment';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { ChevronDown } from 'lucide-react';
import type { OidcProviderDto, OidcProviderTypeDto } from '../../../api/generated';
import { PasswordField } from '../../auth/PasswordField';
import {
  useCreateOidcProvider,
  useDiscoverOidcEndpoints,
  useUpdateOidcProvider,
} from '../../../api/hooks/useOidcProviders';
import { apiErrorMessage } from '../../../utils/apiError';
import { PROVIDER_TYPES } from './oidcProviderTypes';

interface OidcProviderFormDialogProps {
  open: boolean;
  mode: 'create' | 'edit';
  provider?: OidcProviderDto;
  onClose: () => void;
}

/** Trims a field, returning undefined for blank so optional values are omitted. */
function optional(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * Create or edit an OIDC/OAuth2 identity provider. State is seeded once from
 * props via useState initializers; the parent passes a changing `key` so the
 * dialog remounts fresh on every open (no reset-via-effect). The client secret
 * is write-only: blank on edit keeps the stored secret.
 */
export function OidcProviderFormDialog({
  open,
  mode,
  provider,
  onClose,
}: OidcProviderFormDialogProps) {
  const createProvider = useCreateOidcProvider();
  const updateProvider = useUpdateOidcProvider();
  const discover = useDiscoverOidcEndpoints();

  const editing = mode === 'edit' && provider;
  const [name, setName] = useState(editing ? provider.name : '');
  const [providerType, setProviderType] = useState<OidcProviderTypeDto>(
    editing ? provider.providerType : 'OIDC',
  );
  const [clientId, setClientId] = useState(editing ? provider.clientId : '');
  const [clientSecret, setClientSecret] = useState('');
  const [issuerUri, setIssuerUri] = useState(editing ? (provider.issuerUri ?? '') : '');
  const [scope, setScope] = useState(editing ? provider.scope : 'openid email profile');
  const [authorizationUri, setAuthorizationUri] = useState(
    editing ? (provider.authorizationUri ?? '') : '',
  );
  const [tokenUri, setTokenUri] = useState(editing ? (provider.tokenUri ?? '') : '');
  const [userInfoUri, setUserInfoUri] = useState(editing ? (provider.userInfoUri ?? '') : '');
  const [jwkSetUri, setJwkSetUri] = useState(editing ? (provider.jwkSetUri ?? '') : '');
  const [userNameAttribute, setUserNameAttribute] = useState(
    editing ? (provider.userNameAttribute ?? '') : '',
  );
  const [emailAttribute, setEmailAttribute] = useState(
    editing ? (provider.emailAttribute ?? '') : '',
  );
  const [displayNameAttribute, setDisplayNameAttribute] = useState(
    editing ? (provider.displayNameAttribute ?? '') : '',
  );
  const [enabled, setEnabled] = useState(editing ? provider.enabled : false);
  const [error, setError] = useState<string | null>(null);
  const [discoverNote, setDiscoverNote] = useState<string | null>(null);

  const isEdit = mode === 'edit';
  const submitting = createProvider.isPending || updateProvider.isPending;
  const canSubmit =
    name.trim().length > 0 &&
    clientId.trim().length > 0 &&
    (isEdit || clientSecret.trim().length > 0);

  const runDiscover = async () => {
    setDiscoverNote(null);
    const result = await discover.mutateAsync(issuerUri.trim());
    if (!result.success) {
      setDiscoverNote(result.error ?? 'Discovery failed for this issuer.');
      return;
    }
    if (result.authorizationUri) setAuthorizationUri(result.authorizationUri);
    if (result.tokenUri) setTokenUri(result.tokenUri);
    if (result.userInfoUri) setUserInfoUri(result.userInfoUri);
    if (result.jwkSetUri) setJwkSetUri(result.jwkSetUri);
    setDiscoverNote('Endpoints filled in from the issuer.');
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    const common = {
      name: name.trim(),
      providerType,
      clientId: clientId.trim(),
      issuerUri: optional(issuerUri),
      scope: optional(scope),
      authorizationUri: optional(authorizationUri),
      tokenUri: optional(tokenUri),
      userInfoUri: optional(userInfoUri),
      jwkSetUri: optional(jwkSetUri),
      userNameAttribute: optional(userNameAttribute),
      emailAttribute: optional(emailAttribute),
      displayNameAttribute: optional(displayNameAttribute),
    };
    try {
      if (isEdit && provider) {
        await updateProvider.mutateAsync({
          id: provider.id,
          request: { ...common, enabled, clientSecret: optional(clientSecret) },
        });
      } else {
        await createProvider.mutateAsync({ ...common, clientSecret: clientSecret.trim() });
      }
      onClose();
    } catch (err) {
      setError(apiErrorMessage(err, 'Saving the provider failed. Please check the details.'));
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>{isEdit ? 'Edit provider' : 'Add provider'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <TextField
              label="Display name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              fullWidth
              required
              helperText="Shown on the login button, e.g. “Google” or “Company SSO”."
            />
            <TextField
              label="Provider type"
              select
              value={providerType}
              onChange={(e) => setProviderType(e.target.value as OidcProviderTypeDto)}
              fullWidth
            >
              {PROVIDER_TYPES.map((type) => (
                <MenuItem key={type.value} value={type.value}>
                  {type.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Client ID"
              value={clientId}
              onChange={(e) => setClientId(e.target.value)}
              fullWidth
              required
              autoComplete="off"
            />
            <PasswordField
              label={isEdit ? 'Client secret (leave blank to keep)' : 'Client secret'}
              value={clientSecret}
              onChange={setClientSecret}
              autoComplete="new-password"
              required={!isEdit}
            />
            <TextField
              label="Issuer URL"
              value={issuerUri}
              onChange={(e) => setIssuerUri(e.target.value)}
              fullWidth
              placeholder="https://accounts.example.com"
              helperText="For OIDC issuers, use Discover to fill the endpoints automatically."
              slotProps={{
                input: {
                  endAdornment: (
                    <InputAdornment position="end">
                      <Button
                        size="small"
                        onClick={runDiscover}
                        disabled={issuerUri.trim().length === 0 || discover.isPending}
                      >
                        {discover.isPending ? 'Discovering…' : 'Discover'}
                      </Button>
                    </InputAdornment>
                  ),
                },
              }}
            />
            {discoverNote && (
              <Alert severity={discover.data?.success ? 'success' : 'warning'}>
                {discoverNote}
              </Alert>
            )}
            <TextField
              label="Scopes"
              value={scope}
              onChange={(e) => setScope(e.target.value)}
              fullWidth
              helperText="Space-separated, e.g. “openid email profile”."
            />

            <Accordion disableGutters elevation={0} sx={{ '&:before': { display: 'none' } }}>
              <AccordionSummary expandIcon={<ChevronDown size={18} />} sx={{ px: 0 }}>
                <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                  Endpoints &amp; attribute mapping (advanced)
                </Typography>
              </AccordionSummary>
              <AccordionDetails sx={{ px: 0 }}>
                <Stack spacing={2}>
                  <Typography color="text.secondary" sx={{ fontSize: 13 }}>
                    Optional. Required for non-discoverable OAuth2 providers; leave blank for
                    GitHub/Google/Facebook (qnop knows their endpoints).
                  </Typography>
                  <TextField
                    label="Authorization URL"
                    value={authorizationUri}
                    onChange={(e) => setAuthorizationUri(e.target.value)}
                    fullWidth
                  />
                  <TextField
                    label="Token URL"
                    value={tokenUri}
                    onChange={(e) => setTokenUri(e.target.value)}
                    fullWidth
                  />
                  <TextField
                    label="User info URL"
                    value={userInfoUri}
                    onChange={(e) => setUserInfoUri(e.target.value)}
                    fullWidth
                  />
                  <TextField
                    label="JWK set URL"
                    value={jwkSetUri}
                    onChange={(e) => setJwkSetUri(e.target.value)}
                    fullWidth
                  />
                  <TextField
                    label="Username attribute"
                    value={userNameAttribute}
                    onChange={(e) => setUserNameAttribute(e.target.value)}
                    fullWidth
                    placeholder="sub"
                  />
                  <TextField
                    label="Email attribute"
                    value={emailAttribute}
                    onChange={(e) => setEmailAttribute(e.target.value)}
                    fullWidth
                    placeholder="email"
                  />
                  <TextField
                    label="Display-name attribute"
                    value={displayNameAttribute}
                    onChange={(e) => setDisplayNameAttribute(e.target.value)}
                    fullWidth
                    placeholder="name"
                  />
                </Stack>
              </AccordionDetails>
            </Accordion>

            {isEdit && (
              <FormControlLabel
                control={
                  <Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                }
                label={enabled ? 'Enabled for login' : 'Disabled'}
              />
            )}

            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={submitting || !canSubmit}>
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
