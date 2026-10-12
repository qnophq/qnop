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
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import Paper from '@mui/material/Paper';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import MenuItem from '@mui/material/MenuItem';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { useAuthStore } from '../stores/authStore';
import { useConfig } from '../api/hooks/useConfig';
import { OPT_OUT_KEY } from '../tracking/trackingGate';
import { clearConsent } from '../tracking/consent';
import { useReviews } from '../api/hooks/useReviews';
import { useUploadMyAvatar, useRemoveMyAvatar } from '../api/hooks/useAvatar';
import { AchievementRow } from '../components/profile/AchievementRow';
import { profileAchievements, profileStats } from '../components/profile/profileModel';
import { UserAvatar } from '../components/shell/UserAvatar';
import { UserRoleBadge, UserSourceBadge } from '../components/admin/users/UserBadges';
import { AvatarUploader } from '../components/profile/AvatarUploader';
import { TimezoneSetting } from '../components/profile/TimezoneSetting';
import {
  EMAIL_REVIEW_NOTIFICATIONS_KEY,
  useUpdateUserSettings,
  useUserSettings,
} from '../api/hooks/useUserSettings';
import { useDisplayTimezone } from '../api/hooks/useDisplayTimezone';
import { TIMEZONE_SETTING_KEY } from '../utils/timezone';
import { CADENCE_OPTIONS, normalizeCadence } from '../utils/reviewMailCadence';
import { useTranslation } from 'react-i18next';
import { useExplicitLanguage, useInheritedLanguage } from '../api/hooks/useDisplayLanguage';
import { LanguageSetting } from '../components/profile/LanguageSetting';
import { PREFERRED_LANGUAGE_SETTING_KEY } from '../i18n/locales';

/** A review-mail cadence value, the key segment of its catalog label and help text. */
type Cadence = (typeof CADENCE_OPTIONS)[number]['value'];

type Toast = { message: string; severity: 'success' | 'error' } | null;

/**
 * Self-service profile screen (issue #117): an identity header plus the profile-picture control.
 * The picture is independent of the account source, so OIDC users can set one too. Built to grow —
 * display-name/email editing can slot in alongside the avatar section later.
 */
export function ProfilePage() {
  const displayName = useAuthStore((s) => s.displayName);
  const email = useAuthStore((s) => s.email);
  const role = useAuthStore((s) => s.role);
  const source = useAuthStore((s) => s.source);
  const avatarUrl = useAuthStore((s) => s.avatarUrl);

  const upload = useUploadMyAvatar();
  const remove = useRemoveMyAvatar();
  const [toast, setToast] = useState<Toast>(null);
  const busy = upload.isPending || remove.isPending;

  const theme = useTheme();
  const userId = useAuthStore((s) => s.userId);
  const reviewsQuery = useReviews({ page: 0, size: 100, sort: 'updatedAt,desc' });
  const settingsQuery = useUserSettings();
  const updateSettings = useUpdateUserSettings();
  const reviewMailsSetting = settingsQuery.data?.settings.find(
    (setting) => setting.key === EMAIL_REVIEW_NOTIFICATIONS_KEY,
  );
  // Absent value = registry default, which the server reports in the same list —
  // so the control shows what will actually happen rather than a guess (#680).
  const reviewMailsCadence = normalizeCadence(reviewMailsSetting?.value);

  const { t } = useTranslation(['profile', 'common']);
  // The zone actually applied (own preference → workspace default → UTC); an empty stored
  // value means the user has made no explicit choice yet (issue #465).
  const displayZone = useDisplayTimezone();
  const timezoneExplicit = Boolean(
    settingsQuery.data?.settings.find((setting) => setting.key === TIMEZONE_SETTING_KEY)?.value,
  );

  const reviews = reviewsQuery.data?.items ?? [];
  const stats = profileStats(reviews, userId);
  const achievements = profileAchievements({
    reviews,
    userId,
    hasAvatar: Boolean(avatarUrl),
    // Any cadence but OFF counts as being reachable (#680).
    notificationsOn: reviewMailsCadence !== 'OFF',
  });
  const earnedCount = achievements.filter((a) => a.earned).length;

  const onChangeReviewMails = (cadence: string) => {
    updateSettings.mutate(
      { [EMAIL_REVIEW_NOTIFICATIONS_KEY]: cadence },
      {
        onError: () => setToast({ message: t('toast.settingSaveFailed'), severity: 'error' }),
      },
    );
  };

  // Usage measurement (issue #666). Only offered where the deployment measures
  // anything at all — a switch for a feature that is off would be a puzzle.
  const trackingConfigured = Boolean(useConfig().data?.tracking);
  const optedOut =
    settingsQuery.data?.settings.find((setting) => setting.key === OPT_OUT_KEY)?.value === 'true';

  const onToggleTracking = (measured: boolean) => {
    updateSettings.mutate(
      { [OPT_OUT_KEY]: String(!measured) },
      {
        onError: () => setToast({ message: t('toast.settingSaveFailed'), severity: 'error' }),
      },
    );
    // Opting back in means the browser question is asked again rather than
    // silently answered from an old click.
    if (measured) {
      clearConsent();
    }
  };

  const onChangeTimezone = (zone: string) => {
    updateSettings.mutate(
      { [TIMEZONE_SETTING_KEY]: zone },
      {
        onError: () => setToast({ message: t('toast.timezoneSaveFailed'), severity: 'error' }),
        onSuccess: () => setToast({ message: t('toast.timezoneUpdated'), severity: 'success' }),
      },
    );
  };

  const explicitLanguage = useExplicitLanguage();
  const inheritedLanguage = useInheritedLanguage();
  const onChangeLanguage = (language: string) => {
    updateSettings.mutate(
      { [PREFERRED_LANGUAGE_SETTING_KEY]: language },
      {
        onError: () => setToast({ message: t('common:language.saveFailed'), severity: 'error' }),
        onSuccess: () => setToast({ message: t('common:language.updated'), severity: 'success' }),
      },
    );
  };

  const onSelect = async (blob: Blob) => {
    try {
      await upload.mutateAsync(blob);
      setToast({ message: t('toast.avatarUpdated'), severity: 'success' });
    } catch {
      setToast({ message: t('toast.avatarUploadFailed'), severity: 'error' });
    }
  };

  const onRemove = async () => {
    try {
      await remove.mutateAsync();
      setToast({ message: t('toast.avatarRemoved'), severity: 'success' });
    } catch {
      setToast({ message: t('toast.avatarRemoveFailed'), severity: 'error' });
    }
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1" sx={{ fontSize: 28 }}>
          {t('page.title')}
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          {t('page.subtitle')}
        </Typography>
      </Box>

      {/* The player card (issue #469): identity, the reviewer scoreboard and
          the achievement stickers — the launch pad's language on the profile. */}
      <Paper
        variant="outlined"
        sx={{
          p: { xs: 2.5, sm: 3 },
          borderRadius: '16px',
          background: `
            radial-gradient(50% 110% at 92% 0%, ${alpha(theme.qnop.brand.blue, theme.qnop.mode === 'dark' ? 0.16 : 0.08)} 0%, transparent 100%),
            ${theme.palette.background.paper}
          `,
        }}
      >
        <Box
          sx={{
            display: 'grid',
            gap: 2.5,
            gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) auto' },
            alignItems: 'center',
          }}
        >
          <Stack direction="row" spacing={2.5} sx={{ alignItems: 'center', minWidth: 0 }}>
            <Box
              sx={{
                borderRadius: '50%',
                p: '3px',
                flexShrink: 0,
                border: `2px solid ${alpha(theme.qnop.brand.blue, 0.45)}`,
              }}
            >
              <UserAvatar name={displayName} size={72} imageUrl={avatarUrl} />
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 22, fontWeight: 600, lineHeight: 1.2 }} noWrap>
                {displayName ?? t('page.unknownName')}
              </Typography>
              <Typography color="text.secondary" noWrap>
                {email}
              </Typography>
              <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
                {role && <UserRoleBadge role={role} />}
                {source && <UserSourceBadge source={source} />}
              </Stack>
            </Box>
          </Stack>

          {/* The reviewer scoreboard — the dashboard's numbers language. */}
          <Stack direction="row" spacing={3} sx={{ pr: { md: 1 } }}>
            {[
              { label: t('stats.owned'), value: stats.owned },
              { label: t('stats.reviewing'), value: stats.reviewing },
              { label: t('stats.completed'), value: stats.completed },
            ].map(({ label, value }) => (
              <Box key={label} sx={{ textAlign: 'center' }}>
                <Typography
                  sx={{
                    fontSize: '1.4rem',
                    fontWeight: 800,
                    lineHeight: 1.2,
                    fontVariantNumeric: 'tabular-nums',
                    color: value > 0 ? 'text.primary' : 'text.disabled',
                  }}
                >
                  {value}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {label}
                </Typography>
              </Box>
            ))}
          </Stack>
        </Box>

        <Divider sx={{ my: 2.5 }} />
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 1.5 }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 700 }}>
            {t('achievements.heading')}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t('achievements.earnedCount', { earned: earnedCount, total: achievements.length })}
          </Typography>
        </Stack>
        <AchievementRow achievements={achievements} />
      </Paper>

      <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('avatar.heading')}</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.5, mb: 2.5 }}>
          {t('avatar.description')}
        </Typography>
        <Divider sx={{ mb: 2.5 }} />
        <AvatarUploader
          name={displayName ?? '?'}
          imageUrl={avatarUrl}
          busy={busy}
          onSelect={onSelect}
          onRemove={onRemove}
        />
      </Paper>

      <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('notifications.heading')}</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.5, mb: 2.5 }}>
          {t('notifications.description')}
        </Typography>
        <Divider sx={{ mb: 1.5 }} />
        {/* Three choices rather than a switch (issue #680): with on/off, the only
            honest answer on a busy review was off, and then the one mail that
            mattered was missed along with the ninety that did not. */}
        <TextField
          select
          label={t('notifications.cadenceLabel')}
          value={reviewMailsCadence}
          onChange={(event) => onChangeReviewMails(event.target.value)}
          disabled={settingsQuery.isPending}
          helperText={t(`notifications.cadence.${reviewMailsCadence as Cadence}.help`)}
          sx={{ maxWidth: 420 }}
        >
          {CADENCE_OPTIONS.map((option) => (
            <MenuItem key={option.value} value={option.value}>
              {t(`notifications.cadence.${option.value}.label`)}
            </MenuItem>
          ))}
        </TextField>
        <Typography color="text.secondary" sx={{ fontSize: 13, mt: 1.5 }}>
          {t('notifications.mentionsNote')}
        </Typography>
      </Paper>

      {trackingConfigured && (
        <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('tracking.heading')}</Typography>
          <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.5, mb: 2.5 }}>
            {t('tracking.description')}
          </Typography>
          <Divider sx={{ mb: 1.5 }} />
          <FormControlLabel
            control={
              <Switch
                checked={!optedOut}
                onChange={(event) => onToggleTracking(event.target.checked)}
                disabled={settingsQuery.isPending}
                slotProps={{ input: { 'aria-label': t('tracking.toggle') } }}
              />
            }
            label={t('tracking.toggle')}
          />
        </Paper>
      )}

      <LanguageSetting
        explicit={explicitLanguage}
        inherited={inheritedLanguage}
        saving={updateSettings.isPending}
        onChange={onChangeLanguage}
      />

      <TimezoneSetting
        value={displayZone}
        isExplicit={timezoneExplicit}
        saving={updateSettings.isPending}
        onChange={onChangeTimezone}
      />

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
