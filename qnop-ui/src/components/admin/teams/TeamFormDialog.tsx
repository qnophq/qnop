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
import type { AdminTeamSummary } from '../../../api/generated';
import { useCreateTeam, useUpdateTeam } from '../../../api/hooks/useTeams';
import { apiErrorCode, apiErrorMessage } from '../../../utils/apiError';

interface TeamFormDialogProps {
  open: boolean;
  mode: 'create' | 'edit';
  team?: AdminTeamSummary;
  onClose: () => void;
}

/**
 * Create a team (mode "create") or edit its name, description and enabled state
 * (mode "edit"). State is seeded via useState initializers; the parent remounts
 * the dialog per open (key) so there is no reset-via-effect.
 */
export function TeamFormDialog({ open, mode, team, onClose }: TeamFormDialogProps) {
  const createTeam = useCreateTeam();
  const updateTeam = useUpdateTeam();

  const editing = mode === 'edit' && team;
  const [name, setName] = useState(editing ? team.name : '');
  const [description, setDescription] = useState(editing ? (team.description ?? '') : '');
  const [enabled, setEnabled] = useState(editing ? team.enabled : true);
  const [error, setError] = useState<string | null>(null);

  const submitting = createTeam.isPending || updateTeam.isPending;
  const canSubmit = name.trim().length > 0;
  const isEdit = mode === 'edit';

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      if (isEdit && team) {
        await updateTeam.mutateAsync({ id: team.id, request: { name, description, enabled } });
      } else {
        await createTeam.mutateAsync({ name, description: description || undefined });
      }
      onClose();
    } catch (err) {
      const message =
        apiErrorCode(err) === 'NAME_TAKEN'
          ? 'A team with that name already exists.'
          : apiErrorMessage(err, 'Saving failed. Please try again.');
      setError(message);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>{isEdit ? 'Edit team' : 'Create team'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <TextField
              label="Name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              fullWidth
              required
            />
            <TextField
              label="Description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              fullWidth
              multiline
              minRows={2}
            />
            {isEdit && (
              <FormControlLabel
                control={
                  <Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                }
                label={enabled ? 'Team active' : 'Team disabled'}
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
