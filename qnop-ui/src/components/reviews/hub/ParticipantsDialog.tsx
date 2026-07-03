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
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import ButtonBase from '@mui/material/ButtonBase';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Plus, Search, Users, X } from 'lucide-react';
import type { ParticipantView, PrincipalView } from '../../../api/generated';
import { ParticipantKind } from '../../../api/generated';
import {
  useAddParticipant,
  useParticipants,
  usePrincipalSearch,
  useRemoveParticipant,
} from '../../../api/hooks/useReviews';
import type { ToastSeverity } from '../../admin/layout/useToast';
import { UserAvatar } from '../../shell/UserAvatar';
import { apiErrorCode } from '../../../utils/apiError';

/** Known participant conflicts — codes map to friendly text (never server prose). */
const PARTICIPANT_CONFLICTS: Record<string, string> = {
  DUPLICATE_PARTICIPANT: 'Already a reviewer on this document.',
};

interface ParticipantsDialogProps {
  documentId: string;
  open: boolean;
  onClose: () => void;
  /** Owners manage the reviewer set; participants only see it (ADR-0011). */
  isOwner: boolean;
  /** The owner cannot become a participant, so hide them from the picker. */
  ownUserId: string | null;
  notify: (message: string, severity?: ToastSeverity) => void;
}

function PrincipalIcon({ kind, size }: { kind: ParticipantKind; size: number }) {
  const theme = useTheme();
  if (kind !== ParticipantKind.Team) return null;
  return (
    <Box
      sx={{
        width: size,
        height: size,
        borderRadius: '50%',
        bgcolor: theme.qnop.surface2,
        color: 'text.secondary',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
      }}
    >
      <Users size={Math.round(size / 2)} aria-hidden />
    </Box>
  );
}

/** View & (owner-only) manage the reviewers of one document. */
export function ParticipantsDialog({
  documentId,
  open,
  onClose,
  isOwner,
  ownUserId,
  notify,
}: ParticipantsDialogProps) {
  const theme = useTheme();
  const [query, setQuery] = useState('');

  const participantsQuery = useParticipants(documentId, open);
  const searchQuery = usePrincipalSearch(open && isOwner ? query.trim() : '');
  const addParticipant = useAddParticipant(documentId);
  const removeParticipant = useRemoveParticipant(documentId);

  const participants = participantsQuery.data?.participants ?? [];
  const participantKeys = new Set(participants.map((p) => `${p.kind}:${p.principalId}`));
  const candidates = (searchQuery.data?.principals ?? []).filter(
    (principal) =>
      !participantKeys.has(`${principal.kind}:${principal.id}`) &&
      !(principal.kind === ParticipantKind.User && principal.id === ownUserId),
  );

  const handleAdd = (principal: PrincipalView) => {
    addParticipant.mutate(
      principal.kind === ParticipantKind.Team ? { teamId: principal.id } : { userId: principal.id },
      {
        onSuccess: () => notify(`${principal.displayName} added.`),
        onError: (error) =>
          notify(
            PARTICIPANT_CONFLICTS[apiErrorCode(error) ?? ''] ?? 'The reviewer could not be added.',
            'error',
          ),
      },
    );
  };

  const handleRemove = (participant: ParticipantView) => {
    removeParticipant.mutate(participant.id, {
      onSuccess: () => notify(`${participant.displayName} removed.`),
      onError: () => notify('The reviewer could not be removed.', 'error'),
    });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Participants</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5}>
          {participants.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {participantsQuery.isPending ? 'Loading…' : 'No reviewers on this review yet.'}
            </Typography>
          ) : (
            <Stack spacing={0.5} data-testid="participants-list">
              {participants.map((participant) => (
                <Stack
                  key={participant.id}
                  direction="row"
                  spacing={1.25}
                  sx={{ alignItems: 'center', py: 0.5 }}
                >
                  {participant.kind === ParticipantKind.Team ? (
                    <PrincipalIcon kind={participant.kind} size={28} />
                  ) : (
                    <UserAvatar name={participant.displayName} size={28} />
                  )}
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 500 }}>
                      {participant.displayName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {participant.kind === ParticipantKind.Team ? 'Team' : 'User'}
                    </Typography>
                  </Box>
                  {isOwner && (
                    <Tooltip title={`Remove ${participant.displayName}`}>
                      <IconButton
                        size="small"
                        aria-label={`Remove ${participant.displayName}`}
                        disabled={removeParticipant.isPending}
                        onClick={() => handleRemove(participant)}
                      >
                        <X size={15} />
                      </IconButton>
                    </Tooltip>
                  )}
                </Stack>
              ))}
            </Stack>
          )}

          {isOwner && (
            <Box>
              <TextField
                size="small"
                fullWidth
                placeholder="Add people or teams…"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                sx={{ mb: 1 }}
                slotProps={{
                  input: {
                    startAdornment: (
                      <InputAdornment position="start">
                        <Search size={15} />
                      </InputAdornment>
                    ),
                  },
                }}
              />
              <Box
                sx={{
                  border: `1px solid ${theme.palette.divider}`,
                  borderRadius: 2,
                  overflow: 'hidden',
                  maxHeight: 220,
                  overflowY: 'auto',
                }}
              >
                {candidates.length === 0 ? (
                  <Typography variant="body2" color="text.disabled" sx={{ px: 2, py: 1.5 }}>
                    {searchQuery.isPending ? 'Searching…' : 'No matching people or teams.'}
                  </Typography>
                ) : (
                  candidates.map((principal, i) => (
                    <ButtonBase
                      key={`${principal.kind}:${principal.id}`}
                      onClick={() => handleAdd(principal)}
                      disabled={addParticipant.isPending}
                      data-testid={`add-principal-${principal.id}`}
                      sx={{
                        display: 'flex',
                        width: '100%',
                        alignItems: 'center',
                        gap: 1.25,
                        px: 1.5,
                        py: 1,
                        textAlign: 'left',
                        borderBottom:
                          i < candidates.length - 1 ? `1px solid ${theme.palette.divider}` : 'none',
                        '&:hover': { bgcolor: theme.qnop.surface2 },
                        '&:focus-visible': { boxShadow: `inset ${theme.qnop.focusRing}` },
                      }}
                    >
                      {principal.kind === ParticipantKind.Team ? (
                        <PrincipalIcon kind={principal.kind} size={26} />
                      ) : (
                        <UserAvatar name={principal.displayName} size={26} />
                      )}
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography variant="body2" noWrap>
                          {principal.displayName}
                        </Typography>
                      </Box>
                      <Plus size={14} aria-hidden style={{ color: theme.palette.text.disabled }} />
                    </ButtonBase>
                  ))
                )}
              </Box>
            </Box>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} color="inherit">
          Close
        </Button>
      </DialogActions>
    </Dialog>
  );
}
