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

import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { MailX } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { PersonLink } from '../../dashboard/PersonLink';
import { useFormatters } from '../../../hooks/useFormatters';
import { ToneBadge } from '../ToneBadge';

/** What the list needs from a pending entry — both the lead and the admin DTO carry it. */
export interface PendingInvitation {
  userId: string;
  displayName: string;
  slug?: string | null;
  avatarUrl?: string | null;
  joinedAt: string;
}

interface PendingInvitationsListProps {
  invitations: PendingInvitation[];
  onRevoke: (invitation: PendingInvitation) => void;
  /** The user id whose revoke is in flight, to disable its button. */
  revokingUserId?: string | null;
}

/**
 * A team's pending invitations (issue #936, ADR-0064), for whoever may manage the
 * team. An invited user has no access to the team until they accept, so these sit
 * apart from the member roster, each with a way to withdraw the invitation.
 * Renders nothing when there are none.
 */
export function PendingInvitationsList({
  invitations,
  onRevoke,
  revokingUserId,
}: PendingInvitationsListProps) {
  const { t } = useTranslation('teams');
  const { formatDateTime } = useFormatters();
  if (invitations.length === 0) return null;
  return (
    <Paper variant="outlined" component="section" aria-labelledby="pending-invitations-title">
      <Stack spacing={0.5} sx={{ px: 2.5, pt: 2, pb: 1 }}>
        <Typography id="pending-invitations-title" component="h2" sx={{ fontWeight: 700 }}>
          {t('pendingInvitations.title', { count: invitations.length })}
        </Typography>
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
          {t('pendingInvitations.hint')}
        </Typography>
      </Stack>
      <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {invitations.map((invitation) => (
          <Stack
            component="li"
            key={invitation.userId}
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
              <PersonLink
                userId={invitation.userId}
                slug={invitation.slug}
                name={invitation.displayName}
                avatarUrl={invitation.avatarUrl}
                size={34}
              />
              <ToneBadge tone="amber" label={t('pendingInvitations.status')} />
            </Stack>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              {t('pendingInvitations.invitedAt', { date: formatDateTime(invitation.joinedAt) })}
            </Typography>
            <Button
              size="small"
              color="inherit"
              startIcon={<MailX size={15} />}
              disabled={revokingUserId === invitation.userId}
              aria-label={t('pendingInvitations.revokeAria', { name: invitation.displayName })}
              onClick={() => onRevoke(invitation)}
            >
              {t('pendingInvitations.revoke')}
            </Button>
          </Stack>
        ))}
      </Stack>
    </Paper>
  );
}
