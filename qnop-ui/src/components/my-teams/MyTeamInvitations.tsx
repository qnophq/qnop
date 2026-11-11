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

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Check, MailOpen, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { MyTeamInvitation } from '../../api/generated';
import {
  useAcceptTeamInvitation,
  useDeclineTeamInvitation,
  useMyTeamInvitations,
} from '../../api/hooks/useMyTeams';
import { useFormatters } from '../../hooks/useFormatters';
import { apiErrorMessage } from '../../utils/apiError';
import { TeamAvatar } from '../shell/TeamAvatar';

/**
 * The caller's pending team invitations (issue #936, ADR-0064), on top of "My
 * Teams". A team lead's add only invites: the user gets no access to the team or
 * its reviews until they accept here — or they decline, which deletes the
 * invitation. Renders nothing while loading or when there are none.
 */
export function MyTeamInvitations() {
  const { t } = useTranslation('teams');
  const { formatDateTime } = useFormatters();
  const { data } = useMyTeamInvitations();
  const accept = useAcceptTeamInvitation();
  const decline = useDeclineTeamInvitation();
  const invitations = data?.items ?? [];
  if (invitations.length === 0) return null;

  const pendingTeamId = accept.isPending
    ? accept.variables
    : decline.isPending
      ? decline.variables
      : null;
  const failure = accept.error ?? decline.error;

  const subtitle = (invitation: MyTeamInvitation) => {
    const date = formatDateTime(invitation.invitedAt);
    return invitation.invitedByName
      ? t('invitations.invitedBy', { name: invitation.invitedByName, date })
      : t('invitations.invited', { date });
  };

  return (
    <Paper
      variant="outlined"
      component="section"
      aria-labelledby="my-team-invitations-title"
      sx={{ borderColor: 'primary.main', overflow: 'hidden' }}
    >
      <Stack direction="row" spacing={1.25} sx={{ px: 2.5, pt: 2, alignItems: 'center' }}>
        <Box sx={{ color: 'primary.main', display: 'flex' }} aria-hidden>
          <MailOpen size={18} />
        </Box>
        <Typography id="my-team-invitations-title" component="h2" sx={{ fontWeight: 700 }}>
          {t('invitations.title', { count: invitations.length })}
        </Typography>
      </Stack>
      <Typography sx={{ px: 2.5, pt: 0.5, pb: 1, fontSize: 13, color: 'text.secondary' }}>
        {t('invitations.hint')}
      </Typography>
      {failure && (
        <Alert severity="error" sx={{ mx: 2.5, mb: 1 }}>
          {apiErrorMessage(failure, t('invitations.failed'))}
        </Alert>
      )}
      <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {invitations.map((invitation) => {
          const busy = pendingTeamId === invitation.teamId;
          return (
            <Stack
              component="li"
              key={invitation.teamId}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              sx={{
                px: 2.5,
                py: 1.5,
                borderTop: 1,
                borderColor: 'divider',
                alignItems: { sm: 'center' },
              }}
            >
              <Stack
                direction="row"
                spacing={1.5}
                sx={{ alignItems: 'center', flex: 1, minWidth: 0 }}
              >
                <TeamAvatar
                  name={invitation.teamName}
                  imageUrl={invitation.teamAvatarUrl}
                  size={36}
                />
                <Box sx={{ minWidth: 0 }}>
                  <Typography sx={{ fontWeight: 600 }} noWrap>
                    {invitation.teamName}
                  </Typography>
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {subtitle(invitation)}
                  </Typography>
                </Box>
              </Stack>
              <Stack direction="row" spacing={1}>
                <Button
                  size="small"
                  color="inherit"
                  startIcon={<X size={15} />}
                  disabled={busy}
                  aria-label={t('invitations.declineAria', { team: invitation.teamName })}
                  onClick={() => decline.mutate(invitation.teamId)}
                >
                  {t('invitations.decline')}
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  startIcon={<Check size={15} />}
                  disabled={busy}
                  aria-label={t('invitations.acceptAria', { team: invitation.teamName })}
                  onClick={() => accept.mutate(invitation.teamId)}
                >
                  {t('invitations.accept')}
                </Button>
              </Stack>
            </Stack>
          );
        })}
      </Stack>
    </Paper>
  );
}
