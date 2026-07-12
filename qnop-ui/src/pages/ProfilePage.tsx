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
import Switch from '@mui/material/Switch';
import Typography from '@mui/material/Typography';
import { useAuthStore } from '../stores/authStore';
import { useUploadMyAvatar, useRemoveMyAvatar } from '../api/hooks/useAvatar';
import { UserAvatar } from '../components/shell/UserAvatar';
import { UserRoleBadge, UserSourceBadge } from '../components/admin/users/UserBadges';
import { AvatarUploader } from '../components/profile/AvatarUploader';
import {
  EMAIL_REVIEW_NOTIFICATIONS_KEY,
  useUpdateUserSettings,
  useUserSettings,
} from '../api/hooks/useUserSettings';

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

  const settingsQuery = useUserSettings();
  const updateSettings = useUpdateUserSettings();
  const reviewMailsSetting = settingsQuery.data?.settings.find(
    (setting) => setting.key === EMAIL_REVIEW_NOTIFICATIONS_KEY,
  );
  // Absent value = registry default (true) — the toggle reflects what the server does.
  const reviewMailsOn = reviewMailsSetting ? reviewMailsSetting.value !== 'false' : true;

  const onToggleReviewMails = (checked: boolean) => {
    updateSettings.mutate(
      { [EMAIL_REVIEW_NOTIFICATIONS_KEY]: String(checked) },
      {
        onError: () => setToast({ message: 'The setting could not be saved.', severity: 'error' }),
      },
    );
  };

  const onSelect = async (blob: Blob) => {
    try {
      await upload.mutateAsync(blob);
      setToast({ message: 'Profile picture updated.', severity: 'success' });
    } catch {
      setToast({
        message: 'The picture could not be uploaded. Please try again.',
        severity: 'error',
      });
    }
  };

  const onRemove = async () => {
    try {
      await remove.mutateAsync();
      setToast({ message: 'Profile picture removed.', severity: 'success' });
    } catch {
      setToast({
        message: 'The picture could not be removed. Please try again.',
        severity: 'error',
      });
    }
  };

  return (
    <Stack spacing={3} sx={{ maxWidth: 720 }}>
      <Box>
        <Typography variant="h1" sx={{ fontSize: 28 }}>
          Profile
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Manage your account and how you appear across qnop.
        </Typography>
      </Box>

      <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction="row" spacing={2.5} sx={{ alignItems: 'center' }}>
          <UserAvatar name={displayName} size={72} imageUrl={avatarUrl} />
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 22, fontWeight: 600, lineHeight: 1.2 }} noWrap>
              {displayName ?? 'Unknown'}
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
      </Paper>

      <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography sx={{ fontSize: 16, fontWeight: 600 }}>Profile picture</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.5, mb: 2.5 }}>
          Upload a photo so teammates recognise you. It is shown across the app; remove it to fall
          back to your initials.
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
        <Typography sx={{ fontSize: 16, fontWeight: 600 }}>Notifications</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.5, mb: 2.5 }}>
          Emails about reviews you are part of — being added as a reviewer, new annotations, replies
          in your discussions, decisions, and status changes.
        </Typography>
        <Divider sx={{ mb: 1.5 }} />
        <FormControlLabel
          control={
            <Switch
              checked={reviewMailsOn}
              onChange={(event) => onToggleReviewMails(event.target.checked)}
              disabled={settingsQuery.isPending}
              slotProps={{ input: { 'aria-label': 'Email me about review activity' } }}
            />
          }
          label="Email me about review activity"
        />
      </Paper>

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
