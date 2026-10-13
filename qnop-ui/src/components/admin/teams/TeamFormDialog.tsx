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
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Autocomplete from '@mui/material/Autocomplete';
import FormControlLabel from '@mui/material/FormControlLabel';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import type { AdminTeamSummary, PrincipalView } from '../../../api/generated';
import { usePrincipalSearch } from '../../../api/hooks/useReviews';
import { UserAvatar } from '../../shell/UserAvatar';
import { useCreateTeam, useUpdateTeam } from '../../../api/hooks/useTeams';
import { useRemoveTeamAvatar, useUploadTeamAvatar } from '../../../api/hooks/useTeamAvatar';
import { AvatarUploader } from '../../profile/AvatarUploader';
import { apiErrorCode, apiErrorMessage, apiFieldErrors } from '../../../utils/apiError';

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
  const { t } = useTranslation(['admin', 'common']);
  const createTeam = useCreateTeam();
  const updateTeam = useUpdateTeam();
  const uploadAvatar = useUploadTeamAvatar();
  const removeAvatar = useRemoveTeamAvatar();

  const editing = mode === 'edit' && team;
  const [name, setName] = useState(editing ? team.name : '');
  const [description, setDescription] = useState(editing ? (team.description ?? '') : '');
  const [enabled, setEnabled] = useState(editing ? team.enabled : true);
  // Public-profile visibility (issue #586); create defaults stay conservative.
  const [showMembers, setShowMembers] = useState(editing ? team.profileShowMembers : false);
  const [showReviews, setShowReviews] = useState(editing ? team.profileShowReviews : false);
  // The mandatory initial lead (issue #586 follow-up) — create mode only; the
  // roster (and with it the lead role) is managed on the team pages afterwards.
  const [lead, setLead] = useState<PrincipalView | null>(null);
  const [leadSearch, setLeadSearch] = useState('');
  const [debouncedLeadSearch, setDebouncedLeadSearch] = useState('');
  useEffect(() => {
    const handle = setTimeout(() => setDebouncedLeadSearch(leadSearch), 300);
    return () => clearTimeout(handle);
  }, [leadSearch]);
  const leadOptionsQuery = usePrincipalSearch(mode === 'edit' ? '' : debouncedLeadSearch);
  const leadOptions = (leadOptionsQuery.data?.principals ?? []).filter((p) => p.kind === 'USER');
  // The failed save itself; its message is resolved at render so it follows a language switch.
  const [error, setError] = useState<{ cause: unknown } | null>(null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  // Field messages from the server arrive as text; a taken name is flagged and translated here.
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [nameTaken, setNameTaken] = useState(false);

  // Avatar: in edit mode we upload/remove immediately against the existing team; in create mode we
  // hold the chosen image and upload it to the new team after it's created (create-then-upload).
  const [avatarUrl, setAvatarUrl] = useState<string | null>(
    editing ? (team.avatarUrl ?? null) : null,
  );
  const [pendingAvatar, setPendingAvatar] = useState<Blob | null>(null);

  const isEdit = mode === 'edit';
  const avatarBusy = uploadAvatar.isPending || removeAvatar.isPending;
  const submitting = createTeam.isPending || updateTeam.isPending || avatarBusy;

  const onSelectAvatar = (blob: Blob) => {
    if (isEdit && team) {
      uploadAvatar.mutate(
        { teamId: team.id, file: blob },
        { onSuccess: (res) => setAvatarUrl(res.avatarUrl ?? null) },
      );
    } else {
      setPendingAvatar(blob);
      setAvatarUrl(URL.createObjectURL(blob));
    }
  };

  const onRemoveAvatar = () => {
    if (isEdit && team) {
      removeAvatar.mutate(team.id, { onSuccess: () => setAvatarUrl(null) });
    } else {
      setPendingAvatar(null);
      setAvatarUrl(null);
    }
  };

  const clientErrors: Record<string, string> = {};
  if (name.trim().length === 0) {
    clientErrors.name = t('teams.form.errors.nameRequired');
  }
  if (!isEdit && lead === null) {
    clientErrors.leadUserId = t('teams.form.errors.leadRequired');
  }

  const fieldError = (field: string): string | undefined =>
    (field === 'name' && nameTaken ? t('teams.form.errors.nameTaken') : undefined) ??
    serverErrors[field] ??
    (submitAttempted ? clientErrors[field] : undefined);

  const clearServer = (field: string) => {
    if (field === 'name') setNameTaken(false);
    setServerErrors((prev) => {
      if (!(field in prev)) return prev;
      const rest = { ...prev };
      delete rest[field];
      return rest;
    });
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    // A name conflict belongs to the previous response; this one decides afresh.
    setNameTaken(false);
    if (Object.keys(clientErrors).length > 0) {
      setSubmitAttempted(true);
      return;
    }
    try {
      if (isEdit && team) {
        await updateTeam.mutateAsync({
          id: team.id,
          request: {
            name,
            description,
            enabled,
            profileShowMembers: showMembers,
            profileShowReviews: showReviews,
          },
        });
      } else {
        const created = await createTeam.mutateAsync({
          name,
          description: description || undefined,
          // Validated above - the submit never runs without a lead.
          leadUserId: (lead as PrincipalView).id,
          enabled,
          profileShowMembers: showMembers,
          profileShowReviews: showReviews,
        });
        // Create-then-upload: the team now has an id, so set the chosen picture on it.
        if (pendingAvatar) {
          await uploadAvatar.mutateAsync({ teamId: created.id, file: pendingAvatar });
        }
      }
      onClose();
    } catch (err) {
      if (apiErrorCode(err) === 'NAME_TAKEN') {
        setServerErrors({});
        setNameTaken(true);
      } else {
        const serverFieldErrors = apiFieldErrors(err);
        if (Object.keys(serverFieldErrors).length > 0) {
          setServerErrors(serverFieldErrors);
        } else {
          setError({ cause: err });
        }
      }
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <Box component="form" onSubmit={onSubmit} noValidate>
        <DialogTitle>
          {isEdit ? t('teams.form.editTitle') : t('teams.form.createTitle')}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <AvatarUploader
              variant="team"
              name={name || t('teams.form.avatarFallbackName')}
              imageUrl={avatarUrl}
              busy={avatarBusy}
              onSelect={onSelectAvatar}
              onRemove={onRemoveAvatar}
            />
            <TextField
              label={t('teams.form.name')}
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                clearServer('name');
              }}
              fullWidth
              required
              error={Boolean(fieldError('name'))}
              helperText={fieldError('name')}
            />
            <TextField
              label={t('teams.form.description')}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              fullWidth
              multiline
              minRows={2}
            />
            {!isEdit && (
              <Autocomplete
                options={leadOptions}
                loading={leadOptionsQuery.isFetching}
                value={lead}
                onChange={(_, value) => {
                  setLead(value);
                  clearServer('leadUserId');
                }}
                onInputChange={(_, value) => setLeadSearch(value)}
                getOptionLabel={(u) => u.displayName}
                isOptionEqualToValue={(a, b) => a.id === b.id}
                filterOptions={(x) => x}
                noOptionsText={t('teams.form.noOptions')}
                // The person, not just a string - the app-wide picker recipe
                // (avatar + name), as in the review wizard's reviewer step.
                renderOption={({ key, ...optionProps }, option) => (
                  <Box
                    component="li"
                    key={key}
                    {...optionProps}
                    sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}
                  >
                    <UserAvatar name={option.displayName} size={22} imageUrl={option.avatarUrl} />
                    <Typography variant="body2">{option.displayName}</Typography>
                  </Box>
                )}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label={t('teams.form.lead')}
                    placeholder={t('teams.form.leadPlaceholder')}
                    required
                    error={Boolean(fieldError('leadUserId'))}
                    helperText={fieldError('leadUserId') ?? t('teams.form.leadHelper')}
                  />
                )}
              />
            )}
            {/* One tight, left-aligned column - the three toggles read as one group. */}
            <Stack spacing={0} sx={{ alignItems: 'flex-start' }}>
              <FormControlLabel
                control={
                  <Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                }
                label={enabled ? t('teams.form.active') : t('teams.form.disabled')}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={showMembers}
                    onChange={(e) => setShowMembers(e.target.checked)}
                    slotProps={{ input: { 'aria-label': t('teams.form.showMembersAria') } }}
                  />
                }
                label={t('teams.form.showMembers')}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={showReviews}
                    onChange={(e) => setShowReviews(e.target.checked)}
                    slotProps={{
                      input: { 'aria-label': t('teams.form.showReviewsAria') },
                    }}
                  />
                }
                label={t('teams.form.showReviews')}
              />
            </Stack>
            {error && (
              <Alert severity="error">
                {apiErrorMessage(error.cause, t('teams.form.errors.saveFailed'))}
              </Alert>
            )}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">
            {t('common:actions.cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={submitting}>
            {isEdit ? t('common:actions.save') : t('common:actions.create')}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
