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
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import type { TeamDetail } from '../../api/generated';
import { useUpdateMyTeam } from '../../api/hooks/useMyTeams';
import { useRemoveMyTeamAvatar, useUploadMyTeamAvatar } from '../../api/hooks/useTeamAvatar';
import { AvatarUploader } from '../profile/AvatarUploader';
import { apiErrorMessage } from '../../utils/apiError';

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
  const updateTeam = useUpdateMyTeam();
  const uploadAvatar = useUploadMyTeamAvatar();
  const removeAvatar = useRemoveMyTeamAvatar();

  const [description, setDescription] = useState(team.description ?? '');
  // The avatar applies immediately (like the admin edit dialog); the preview
  // follows the mutation's fresh URL rather than waiting for a refetch.
  const [avatarUrl, setAvatarUrl] = useState<string | null>(team.avatarUrl ?? null);
  const [error, setError] = useState<string | null>(null);
  const avatarBusy = uploadAvatar.isPending || removeAvatar.isPending;

  const onSelectAvatar = (blob: Blob) => {
    uploadAvatar.mutate(
      { teamId: team.id, file: blob },
      {
        onSuccess: (response) => setAvatarUrl(response.avatarUrl),
        onError: (err) => setError(apiErrorMessage(err, 'Could not upload the team picture.')),
      },
    );
  };
  const onRemoveAvatar = () => {
    removeAvatar.mutate(team.id, {
      onSuccess: () => setAvatarUrl(null),
      onError: (err) => setError(apiErrorMessage(err, 'Could not remove the team picture.')),
    });
  };

  const onSave = async () => {
    setError(null);
    try {
      await updateTeam.mutateAsync({ teamId: team.id, description: description.trim() || null });
      onClose();
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not save the team.'));
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Edit team</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <AvatarUploader
            name={team.name}
            imageUrl={avatarUrl}
            busy={avatarBusy}
            onSelect={onSelectAvatar}
            onRemove={onRemoveAvatar}
          />
          <TextField
            label="Description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
            helperText="What this team reviews — shown wherever the team appears."
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={onSave} disabled={updateTeam.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
