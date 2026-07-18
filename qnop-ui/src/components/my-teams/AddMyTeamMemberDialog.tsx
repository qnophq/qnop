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

import { useEffect, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Autocomplete from '@mui/material/Autocomplete';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import type { PrincipalView, TeamRole } from '../../api/generated';
import { usePrincipalSearch } from '../../api/hooks/useReviews';
import { useAddMyTeamMember } from '../../api/hooks/useMyTeams';
import { apiErrorMessage } from '../../utils/apiError';

interface AddMyTeamMemberDialogProps {
  open: boolean;
  teamId: string;
  existingMemberIds: string[];
  onClose: () => void;
}

/**
 * The lead's add-member dialog (issue #470). Unlike the admin dialog it draws
 * candidates from the non-admin principal directory (users only, display names
 * only) rather than the admin user list, and adds them through the lead client.
 * Adds run in parallel; on a partial failure the dialog stays open with only the
 * users that could not be added still selected.
 */
export function AddMyTeamMemberDialog({
  open,
  teamId,
  existingMemberIds,
  onClose,
}: AddMyTeamMemberDialogProps) {
  const addMember = useAddMyTeamMember();
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [selected, setSelected] = useState<PrincipalView[]>([]);
  const [teamRole, setTeamRole] = useState<TeamRole>('MEMBER');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const handle = setTimeout(() => setDebounced(search), 300);
    return () => clearTimeout(handle);
  }, [search]);

  const { data, isFetching } = usePrincipalSearch(debounced);
  const existing = new Set(existingMemberIds);
  const options = (data?.principals ?? []).filter((p) => p.kind === 'USER' && !existing.has(p.id));

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (selected.length === 0) return;
    setError(null);
    setSubmitting(true);
    const results = await Promise.allSettled(
      selected.map((user) => addMember.mutateAsync({ teamId, userId: user.id, teamRole })),
    );
    setSubmitting(false);

    const failed = selected.filter((_, i) => results[i].status === 'rejected');
    if (failed.length === 0) {
      onClose();
      return;
    }
    // Keep only the users that could not be added so the lead can retry them.
    setSelected(failed);
    const firstError = results.find((r) => r.status === 'rejected') as
      PromiseRejectedResult | undefined;
    setError(
      apiErrorMessage(
        firstError?.reason,
        `Could not add: ${failed.map((u) => u.displayName).join(', ')}.`,
      ),
    );
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>Add members</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <Autocomplete
              multiple
              disableCloseOnSelect
              options={options}
              loading={isFetching}
              value={selected}
              onChange={(_, value) => setSelected(value)}
              onInputChange={(_, value) => setSearch(value)}
              getOptionLabel={(u) => u.displayName}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              filterOptions={(x) => x}
              noOptionsText="No matching users"
              renderInput={(params) => (
                <TextField {...params} label="Users" placeholder="Search by name" />
              )}
            />
            <TextField
              label="Team role"
              select
              value={teamRole}
              onChange={(e) => setTeamRole(e.target.value as TeamRole)}
              fullWidth
              helperText="Applied to everyone added in this batch."
            >
              <MenuItem value="MEMBER">Member</MenuItem>
              <MenuItem value="LEAD">Lead</MenuItem>
            </TextField>
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={submitting || selected.length === 0}>
            {selected.length > 1 ? `Add ${selected.length}` : 'Add'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
