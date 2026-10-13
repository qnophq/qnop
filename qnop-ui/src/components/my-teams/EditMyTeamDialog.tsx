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
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import type { TeamDetail } from '../../api/generated';
import { useUpdateMyTeam } from '../../api/hooks/useMyTeams';
import { useRemoveMyTeamAvatar, useUploadMyTeamAvatar } from '../../api/hooks/useTeamAvatar';
import { AvatarUploader } from '../profile/AvatarUploader';
import { apiErrorMessage } from '../../utils/apiError';

/** A failed action: the error itself and the `teams` catalog key of its fallback message. */
interface DialogError {
  cause: unknown;
  fallbackKey: 'editTeam.errors.upload' | 'editTeam.errors.remove' | 'editTeam.errors.save';
}

/**
 * A lead's team-presentation editor on the My Teams surface (issue #509
 * follow-up): the avatar (upload/replace/remove, applied immediately through
 * the lead endpoints) and the description (saved on submit). Deliberately no
 * name field — renaming, like enabling/disabling, stays an admin concern.
 */
export function EditMyTeamDialog({
  open,
  team,
  onClose,
}: {
  open: boolean;
  team: TeamDetail;
  onClose: () => void;
}) {
  const { t } = useTranslation('teams');
  const updateTeam = useUpdateMyTeam();
  const uploadAvatar = useUploadMyTeamAvatar();
  const removeAvatar = useRemoveMyTeamAvatar();

  const [description, setDescription] = useState(team.description ?? '');
  // Public-profile visibility (issue #586): what /teams/<slug> exposes to
  // non-members. Conservative defaults — exposing is an explicit decision.
  const [showMembers, setShowMembers] = useState(team.profileShowMembers);
  const [showReviews, setShowReviews] = useState(team.profileShowReviews);
  // The avatar applies immediately (like the admin edit dialog); the preview
  // follows the mutation's fresh URL rather than waiting for a refetch.
  const [avatarUrl, setAvatarUrl] = useState<string | null>(team.avatarUrl ?? null);
  // The cause, not its message: resolved at render, so it follows a language switch.
  const [error, setError] = useState<DialogError | null>(null);
  const avatarBusy = uploadAvatar.isPending || removeAvatar.isPending;

  const onSelectAvatar = (blob: Blob) => {
    uploadAvatar.mutate(
      { teamId: team.id, file: blob },
      {
        onSuccess: (response) => setAvatarUrl(response.avatarUrl),
        onError: (err) => setError({ cause: err, fallbackKey: 'editTeam.errors.upload' }),
      },
    );
  };
  const onRemoveAvatar = () => {
    removeAvatar.mutate(team.id, {
      onSuccess: () => setAvatarUrl(null),
      onError: (err) => setError({ cause: err, fallbackKey: 'editTeam.errors.remove' }),
    });
  };

  const onSave = async () => {
    setError(null);
    try {
      await updateTeam.mutateAsync({
        teamId: team.id,
        description: description.trim() || null,
        profileShowMembers: showMembers,
        profileShowReviews: showReviews,
      });
      onClose();
    } catch (err) {
      setError({ cause: err, fallbackKey: 'editTeam.errors.save' });
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t('editTeam.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ pt: 1 }}>
          {error && (
            <Alert severity="error">{apiErrorMessage(error.cause, t(error.fallbackKey))}</Alert>
          )}
          <AvatarUploader
            variant="team"
            name={team.name}
            imageUrl={avatarUrl}
            busy={avatarBusy}
            onSelect={onSelectAvatar}
            onRemove={onRemoveAvatar}
          />
          <TextField
            label={t('editTeam.description')}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
            helperText={t('editTeam.descriptionHelper')}
          />
          <Box>
            <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
              {t('editTeam.publicProfile')}
            </Typography>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1 }}>
              {t('editTeam.publicProfileHint')}
            </Typography>
            <FormControlLabel
              control={
                <Switch
                  checked={showMembers}
                  onChange={(e) => setShowMembers(e.target.checked)}
                  slotProps={{ input: { 'aria-label': t('editTeam.showMembersAria') } }}
                />
              }
              label={t('editTeam.showMembers')}
            />
            <FormControlLabel
              control={
                <Switch
                  checked={showReviews}
                  onChange={(e) => setShowReviews(e.target.checked)}
                  slotProps={{
                    input: { 'aria-label': t('editTeam.showReviewsAria') },
                  }}
                />
              }
              label={t('editTeam.showReviews')}
            />
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('editTeam.cancel')}</Button>
        <Button variant="contained" onClick={onSave} disabled={updateTeam.isPending}>
          {t('editTeam.save')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
