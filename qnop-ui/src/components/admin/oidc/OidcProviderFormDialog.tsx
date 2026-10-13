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

import { useMemo, useState, type FormEvent } from 'react';
import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
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
import type { ParseKeys } from 'i18next';
import { ChevronDown } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import type { OidcProviderDto, OidcProviderTypeDto } from '../../../api/generated';
import { PasswordField } from '../../auth/PasswordField';
import {
  useCreateOidcProvider,
  useDiscoverOidcEndpoints,
  useUpdateOidcProvider,
} from '../../../api/hooks/useOidcProviders';
import { apiErrorMessage } from '../../../utils/apiError';
import { isHttpUrl } from '../../../utils/validation';
import { ProviderCallbackInstructions } from './ProviderCallbackInstructions';
import { PROVIDER_TYPES, supportsDiscovery } from './oidcProviderTypes';

/** Provider types whose scopes qnop fixes; the operator cannot change them. */
const LOCKED_SCOPES: Partial<Record<OidcProviderTypeDto, string>> = {
  GITHUB: 'read:user user:email',
  FACEBOOK: 'email public_profile',
};

/**
 * Example values for the issuer and the claim-name fields. They are technical identifiers (a URL
 * and standard OIDC claim names), so they stay untranslated.
 */
const ISSUER_PLACEHOLDER = 'https://accounts.example.com';
const CLAIM_PLACEHOLDERS = { userName: 'sub', email: 'email', displayName: 'name' } as const;

type AdminKey = ParseKeys<'admin'>;

/** A failed request: the error itself and the `admin` catalog key of its fallback message. */
interface DialogError {
  cause: unknown;
  fallbackKey: AdminKey;
}

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
 * dialog remounts fresh on every open. The client secret is write-only: blank
 * on edit keeps the stored secret. After a fresh create the dialog switches to
 * a success step showing the callback URL to register at the IdP.
 */
export function OidcProviderFormDialog({
  open,
  mode,
  provider,
  onClose,
}: OidcProviderFormDialogProps) {
  const { t } = useTranslation(['admin', 'common']);
  const createProvider = useCreateOidcProvider();
  const updateProvider = useUpdateOidcProvider();
  const discover = useDiscoverOidcEndpoints();

  const isEdit = mode === 'edit';
  const editing = isEdit && provider;
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
  const [error, setError] = useState<DialogError | null>(null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [acknowledgeClientId, setAcknowledgeClientId] = useState(false);
  const [created, setCreated] = useState<OidcProviderDto | null>(null);

  const lockedScope = LOCKED_SCOPES[providerType];
  const effectiveScope = lockedScope ?? scope;
  const isOidc = providerType === 'OIDC';
  const isGeneric = providerType === 'OAUTH2';
  const showIssuer = supportsDiscovery(providerType);
  // Endpoints & attribute mapping are only shown for the generic types. For the
  // well-known vendors (GitHub/Google/Facebook) qnop already knows them, so the
  // whole section is hidden.
  const showEndpoints = isOidc || isGeneric;
  const clientIdChanged = Boolean(editing && clientId.trim() !== provider.clientId);
  const submitting = createProvider.isPending || updateProvider.isPending;

  const errors = useMemo(() => {
    // Catalog keys, resolved at render so the messages follow a language switch.
    const e: Partial<Record<string, AdminKey>> = {};
    if (!name.trim()) e.name = 'oidc.form.errors.nameRequired';
    if (!clientId.trim()) e.clientId = 'oidc.form.errors.clientIdRequired';
    if (!isEdit && !clientSecret.trim()) e.clientSecret = 'oidc.form.errors.clientSecretRequired';
    if (showIssuer && isOidc && !issuerUri.trim()) {
      e.issuerUri = 'oidc.form.errors.issuerRequired';
    }
    if (issuerUri.trim() && !isHttpUrl(issuerUri.trim())) {
      e.issuerUri = 'oidc.form.errors.invalidUrl';
    }
    if (isOidc && effectiveScope.trim() && !effectiveScope.trim().split(/\s+/).includes('openid')) {
      e.scope = 'oidc.form.errors.openidScope';
    }
    if (showEndpoints) {
      const endpoints = [
        ['authorizationUri', authorizationUri],
        ['tokenUri', tokenUri],
        ['userInfoUri', userInfoUri],
        ['jwkSetUri', jwkSetUri],
      ] as const;
      for (const [field, value] of endpoints) {
        if (isGeneric && field !== 'jwkSetUri' && !value.trim()) {
          e[field] = 'oidc.form.errors.endpointRequired';
        } else if (value.trim() && !isHttpUrl(value.trim())) {
          e[field] = 'oidc.form.errors.invalidUrl';
        }
      }
    }
    return e;
  }, [
    name,
    clientId,
    clientSecret,
    isEdit,
    showIssuer,
    showEndpoints,
    isOidc,
    isGeneric,
    issuerUri,
    effectiveScope,
    authorizationUri,
    tokenUri,
    userInfoUri,
    jwkSetUri,
  ]);

  const canSubmit = Object.keys(errors).length === 0 && (!clientIdChanged || acknowledgeClientId);

  const fieldError = (field: string): string | undefined => {
    const key = submitAttempted ? errors[field] : undefined;
    return key ? t(key) : undefined;
  };

  const runDiscover = async () => {
    setError(null);
    try {
      const result = await discover.mutateAsync(issuerUri.trim());
      if (!result.success) return;
      if (result.authorizationUri) setAuthorizationUri(result.authorizationUri);
      if (result.tokenUri) setTokenUri(result.tokenUri);
      if (result.userInfoUri) setUserInfoUri(result.userInfoUri);
      if (result.jwkSetUri) setJwkSetUri(result.jwkSetUri);
    } catch (err) {
      // mutateAsync rethrows on TCP failure / 5xx / rate limit (unlike the
      // endpoint's 200 + success:false). Surface it instead of leaving an
      // unhandled rejection.
      setError({ cause: err, fallbackKey: 'oidc.form.errors.discoverUnreachable' });
    }
  };

  // Editing the issuer invalidates a previous discovery result so the banner
  // never shows endpoints for a different issuer.
  const onIssuerChange = (value: string) => {
    setIssuerUri(value);
    if (discover.data || discover.error) discover.reset();
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitAttempted(true);
    if (!canSubmit) return;
    setError(null);
    const common = {
      name: name.trim(),
      providerType,
      clientId: clientId.trim(),
      issuerUri: optional(issuerUri),
      scope: optional(effectiveScope),
      // Endpoints & attribute mapping only apply to the generic types; for the
      // well-known vendors qnop fills them in, so they are neither shown nor sent.
      ...(showEndpoints
        ? {
            authorizationUri: optional(authorizationUri),
            tokenUri: optional(tokenUri),
            userInfoUri: optional(userInfoUri),
            jwkSetUri: optional(jwkSetUri),
            userNameAttribute: optional(userNameAttribute),
            emailAttribute: optional(emailAttribute),
            displayNameAttribute: optional(displayNameAttribute),
          }
        : {}),
    };
    try {
      if (isEdit && provider) {
        await updateProvider.mutateAsync({
          id: provider.id,
          request: { ...common, enabled, clientSecret: optional(clientSecret) },
        });
        onClose();
      } else {
        const result = await createProvider.mutateAsync({
          ...common,
          clientSecret: clientSecret.trim(),
        });
        setCreated(result);
      }
    } catch (err) {
      setError({ cause: err, fallbackKey: 'oidc.form.errors.save' });
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      {created ? (
        <CreateSuccessStep provider={created} onClose={onClose} />
      ) : (
        <Box component="form" onSubmit={onSubmit} noValidate>
          <DialogTitle>{isEdit ? t('oidc.form.editTitle') : t('oidc.form.addTitle')}</DialogTitle>
          <DialogContent>
            <Stack spacing={2.5} sx={{ mt: 1 }}>
              <TextField
                label={t('oidc.form.displayName')}
                value={name}
                onChange={(e) => setName(e.target.value)}
                fullWidth
                required
                error={Boolean(fieldError('name'))}
                helperText={fieldError('name') ?? t('oidc.form.displayNameHelper')}
              />
              <TextField
                label={t('oidc.form.providerType')}
                select
                value={providerType}
                onChange={(e) => setProviderType(e.target.value as OidcProviderTypeDto)}
                fullWidth
                disabled={isEdit}
                helperText={isEdit ? t('oidc.form.providerTypeLocked') : undefined}
              >
                {PROVIDER_TYPES.map((type) => (
                  <MenuItem key={type.value} value={type.value}>
                    {t(type.labelKey)}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label={t('oidc.form.clientId')}
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
                fullWidth
                required
                autoComplete="off"
                error={Boolean(fieldError('clientId'))}
                helperText={fieldError('clientId')}
              />
              {clientIdChanged && (
                <Alert severity="warning">
                  {t('oidc.form.clientIdChangeWarning')}
                  <FormControlLabel
                    sx={{ mt: 0.5, display: 'flex' }}
                    control={
                      <Checkbox
                        size="small"
                        checked={acknowledgeClientId}
                        onChange={(e) => setAcknowledgeClientId(e.target.checked)}
                      />
                    }
                    label={t('oidc.form.clientIdChangeAcknowledge')}
                  />
                </Alert>
              )}
              <PasswordField
                label={isEdit ? t('oidc.form.clientSecretKeep') : t('oidc.form.clientSecret')}
                value={clientSecret}
                onChange={setClientSecret}
                autoComplete="new-password"
                required={!isEdit}
                error={Boolean(fieldError('clientSecret'))}
                helperText={fieldError('clientSecret')}
              />
              {showIssuer && (
                <TextField
                  label={t('oidc.form.issuer')}
                  value={issuerUri}
                  onChange={(e) => onIssuerChange(e.target.value)}
                  fullWidth
                  required={isOidc}
                  placeholder={ISSUER_PLACEHOLDER}
                  error={Boolean(fieldError('issuerUri'))}
                  helperText={fieldError('issuerUri') ?? t('oidc.form.issuerHelper')}
                  slotProps={{
                    input: {
                      endAdornment: (
                        <InputAdornment position="end">
                          <Button
                            size="small"
                            onClick={runDiscover}
                            disabled={!isHttpUrl(issuerUri.trim()) || discover.isPending}
                          >
                            {discover.isPending
                              ? t('oidc.form.discovering')
                              : t('oidc.form.discover')}
                          </Button>
                        </InputAdornment>
                      ),
                    },
                  }}
                />
              )}
              {discover.data && <DiscoveryBanner result={discover.data} />}
              <TextField
                label={t('oidc.form.scopes')}
                value={effectiveScope}
                onChange={(e) => setScope(e.target.value)}
                fullWidth
                disabled={Boolean(lockedScope)}
                error={Boolean(fieldError('scope'))}
                helperText={
                  fieldError('scope') ??
                  (lockedScope ? t('oidc.form.scopesLocked') : t('oidc.form.scopesHelper'))
                }
              />

              {showEndpoints && (
                <Accordion
                  disableGutters
                  elevation={0}
                  defaultExpanded={isGeneric}
                  sx={{ '&:before': { display: 'none' } }}
                >
                  <AccordionSummary expandIcon={<ChevronDown size={18} />} sx={{ px: 0 }}>
                    <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                      {isGeneric
                        ? t('oidc.form.endpointsRequired')
                        : t('oidc.form.endpointsAdvanced')}
                    </Typography>
                  </AccordionSummary>
                  <AccordionDetails sx={{ px: 0 }}>
                    <Stack spacing={2}>
                      <Typography color="text.secondary" sx={{ fontSize: 13 }}>
                        {isGeneric
                          ? t('oidc.form.endpointsRequiredHint')
                          : t('oidc.form.endpointsOptionalHint')}
                      </Typography>
                      <TextField
                        label={t('oidc.form.authorizationUrl')}
                        value={authorizationUri}
                        onChange={(e) => setAuthorizationUri(e.target.value)}
                        fullWidth
                        required={isGeneric}
                        error={Boolean(fieldError('authorizationUri'))}
                        helperText={fieldError('authorizationUri')}
                      />
                      <TextField
                        label={t('oidc.form.tokenUrl')}
                        value={tokenUri}
                        onChange={(e) => setTokenUri(e.target.value)}
                        fullWidth
                        required={isGeneric}
                        error={Boolean(fieldError('tokenUri'))}
                        helperText={fieldError('tokenUri')}
                      />
                      <TextField
                        label={t('oidc.form.userInfoUrl')}
                        value={userInfoUri}
                        onChange={(e) => setUserInfoUri(e.target.value)}
                        fullWidth
                        required={isGeneric}
                        error={Boolean(fieldError('userInfoUri'))}
                        helperText={fieldError('userInfoUri')}
                      />
                      <TextField
                        label={t('oidc.form.jwkSetUrl')}
                        value={jwkSetUri}
                        onChange={(e) => setJwkSetUri(e.target.value)}
                        fullWidth
                        error={Boolean(fieldError('jwkSetUri'))}
                        helperText={fieldError('jwkSetUri')}
                      />
                      <TextField
                        label={t('oidc.form.userNameAttribute')}
                        value={userNameAttribute}
                        onChange={(e) => setUserNameAttribute(e.target.value)}
                        fullWidth
                        placeholder={CLAIM_PLACEHOLDERS.userName}
                      />
                      <TextField
                        label={t('oidc.form.emailAttribute')}
                        value={emailAttribute}
                        onChange={(e) => setEmailAttribute(e.target.value)}
                        fullWidth
                        placeholder={CLAIM_PLACEHOLDERS.email}
                      />
                      <TextField
                        label={t('oidc.form.displayNameAttribute')}
                        value={displayNameAttribute}
                        onChange={(e) => setDisplayNameAttribute(e.target.value)}
                        fullWidth
                        placeholder={CLAIM_PLACEHOLDERS.displayName}
                      />
                    </Stack>
                  </AccordionDetails>
                </Accordion>
              )}

              {isEdit && provider && (
                <ProviderCallbackInstructions
                  providerId={provider.id}
                  providerType={providerType}
                  variant="inline"
                />
              )}

              {isEdit && (
                <FormControlLabel
                  control={
                    <Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                  }
                  label={enabled ? t('oidc.form.enabledForLogin') : t('oidc.status.disabled')}
                />
              )}

              {error && (
                <Alert severity="error">{apiErrorMessage(error.cause, t(error.fallbackKey))}</Alert>
              )}
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2.5 }}>
            <Button onClick={onClose} color="inherit">
              {t('common:actions.cancel')}
            </Button>
            <Button type="submit" variant="contained" disabled={submitting || !canSubmit}>
              {isEdit ? t('common:actions.save') : t('common:actions.create')}
            </Button>
          </DialogActions>
        </Box>
      )}
    </Dialog>
  );
}

interface DiscoveryBannerProps {
  result: {
    success: boolean;
    error?: string;
    authorizationUri?: string;
    tokenUri?: string;
    userInfoUri?: string;
    jwkSetUri?: string;
  };
}

/** Shows the outcome of a discovery probe — the four resolved endpoints, or the error. */
function DiscoveryBanner({ result }: DiscoveryBannerProps) {
  const { t } = useTranslation('admin');
  if (!result.success) {
    // The server's own reason is shown verbatim; only the fallback is ours.
    return <Alert severity="warning">{result.error ?? t('oidc.form.discovery.failed')}</Alert>;
  }
  const rows: [AdminKey, string | undefined][] = [
    ['oidc.form.discovery.authorization', result.authorizationUri],
    ['oidc.form.discovery.token', result.tokenUri],
    ['oidc.form.discovery.userInfo', result.userInfoUri],
    ['oidc.form.discovery.jwkSet', result.jwkSetUri],
  ];
  return (
    <Alert severity="success" sx={{ '& .MuiAlert-message': { width: '100%' } }}>
      <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
        {t('oidc.form.discovery.succeeded')}
      </Typography>
      <Stack spacing={0.25}>
        {rows.map(([label, value]) => (
          <Typography
            key={label}
            variant="caption"
            sx={{ display: 'block', wordBreak: 'break-all', color: 'text.secondary' }}
          >
            <strong>{t(label)}:</strong> {value ?? '—'}
          </Typography>
        ))}
      </Stack>
    </Alert>
  );
}

interface CreateSuccessStepProps {
  provider: OidcProviderDto;
  onClose: () => void;
}

/**
 * Post-create hero: the provider is saved but disabled. The one thing left for
 * the operator is registering the callback URL at the IdP, so that panel is the
 * focus, followed by a reminder to enable the provider once the IdP side is set.
 */
function CreateSuccessStep({ provider, onClose }: CreateSuccessStepProps) {
  const { t } = useTranslation(['admin', 'common']);
  return (
    <>
      <DialogTitle>{t('oidc.created.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            <Trans
              t={t}
              i18nKey="oidc.created.message"
              // The provider name goes in as a child, never an interpolated value: Trans parses
              // the interpolated string for tags, so a name like "<b>…" would turn into markup.
              components={{ name: <strong>{provider.name}</strong>, strong: <strong /> }}
            />
          </Typography>
          <ProviderCallbackInstructions
            providerId={provider.id}
            providerType={provider.providerType}
            variant="success"
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} variant="contained">
          {t('common:actions.done')}
        </Button>
      </DialogActions>
    </>
  );
}
