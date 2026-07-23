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

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Eye, FileText, User } from 'lucide-react';
import type { ParticipantView } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';
import { UserHoverCard } from '../../people/UserHoverCard';
import { UserAvatar } from '../../shell/UserAvatar';
import { TeamAvatar } from '../../shell/TeamAvatar';
import { avatarSrc, teamAvatarSrc } from '../../../utils/avatarUrl';

/** Shared bits of the reviews overview: role badge, doc icon, progress, reviewer stack. */

export function RoleBadge({ role }: { role: 'owner' | 'reviewer' }) {
  return role === 'owner' ? (
    <ToneBadge tone="blue" label="Owner" />
  ) : (
    <ToneBadge tone="neutral" label="Reviewer" />
  );
}

export function RoleIcon({ role }: { role: 'owner' | 'reviewer' }) {
  return role === 'owner' ? <User size={12} aria-hidden /> : <Eye size={12} aria-hidden />;
}

/** The little document sheet icon leading every row/card (prototype). */
export function DocumentIcon({ size = 30 }: { size?: number }) {
  const theme = useTheme();
  return (
    <Box
      aria-hidden
      sx={{
        width: size,
        height: Math.round(size * 1.2),
        borderRadius: '5px',
        bgcolor: theme.qnop.surface2,
        border: `1px solid ${theme.palette.divider}`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
        color: 'text.secondary',
      }}
    >
      <FileText size={Math.round(size / 2)} />
    </Box>
  );
}

/**
 * Decided/total bar in the status colour. `discussion` (issue #393) adds the
 * prototype's second tone: open-but-discussed annotations trail the resolved
 * segment in amber, so the strip reads "done · in flight · untouched".
 */
export function ProgressBar({
  resolved,
  total,
  color,
  discussion = 0,
}: {
  resolved: number;
  total: number;
  color: string;
  discussion?: number;
}) {
  const theme = useTheme();
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <Box
        sx={{
          width: 54,
          height: 5,
          borderRadius: 99,
          bgcolor: theme.palette.divider,
          overflow: 'hidden',
          display: 'flex',
        }}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={resolved}
        aria-label={`${resolved} of ${total} annotations resolved`}
      >
        <Box sx={{ width: `${(resolved / total) * 100}%`, height: '100%', bgcolor: color }} />
        {discussion > 0 && (
          <Box
            sx={{
              width: `${(discussion / total) * 100}%`,
              height: '100%',
              bgcolor: theme.palette.warning.main,
            }}
          />
        )}
      </Box>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}
      >
        {resolved}/{total}
      </Typography>
    </Stack>
  );
}

/** Overlapping reviewer avatars with real display names (max 3 + counter). */
export function ReviewerStack({
  participants,
  anonymous = false,
}: {
  participants: ParticipantView[];
  /** True for an anonymous review — its roster ids are synthetic (issue #422), so no hover cards. */
  anonymous?: boolean;
}) {
  if (participants.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary">
        no reviewers
      </Typography>
    );
  }
  const shown = participants.slice(0, 3);
  return (
    <Stack direction="row" sx={{ alignItems: 'center' }}>
      {shown.map((participant, index) => {
        const cardUserId =
          participant.kind === 'USER' && !anonymous ? participant.principalId : null;
        const avatar = (
          <Box
            sx={{
              borderRadius: '50%',
              border: '2px solid',
              borderColor: 'background.paper',
              ml: index === 0 ? 0 : -0.75,
              display: 'flex',
              zIndex: shown.length - index,
            }}
          >
            {participant.kind === 'TEAM' ? (
              // Team picture (issue #509); an anonymised roster carries a
              // synthetic token instead of the team id, so no URL is built —
              // the initials fallback keeps the pseudonym airtight.
              <TeamAvatar
                name={participant.displayName}
                size={24}
                imageUrl={anonymous ? null : teamAvatarSrc(participant.principalId)}
              />
            ) : (
              <UserAvatar
                name={participant.displayName}
                size={24}
                // Public read path (ADR-0031); a 404 quietly falls back to initials.
                imageUrl={avatarSrc(participant.principalId)}
              />
            )}
          </Box>
        );
        return (
          <UserHoverCard
            key={participant.id}
            userId={cardUserId}
            slug={participant.slug}
            profileName={participant.displayName}
          >
            {/* The card names the person already — the tooltip only steps in
                where no card may attach (teams, anonymised rosters). */}
            {cardUserId ? avatar : <Tooltip title={participant.displayName}>{avatar}</Tooltip>}
          </UserHoverCard>
        );
      })}
      {participants.length > 3 && (
        <Typography variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>
          +{participants.length - 3}
        </Typography>
      )}
    </Stack>
  );
}

/** The review's owner as a compact identity chip (issue #469): picture + name. */
export function OwnerChip({
  ownerId,
  slug,
  name,
}: {
  ownerId: string;
  slug?: string | null;
  name?: string | null;
}) {
  return (
    // Ownership is structurally public (issue #472), so the hover card may
    // attach even on anonymous reviews.
    <UserHoverCard userId={ownerId} slug={slug} profileName={name ?? undefined}>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
        <UserAvatar name={name ?? '?'} size={20} imageUrl={avatarSrc(ownerId)} />
        <Typography variant="caption" noWrap sx={{ color: 'text.secondary', maxWidth: 140 }}>
          {name ?? '—'}
        </Typography>
      </Stack>
    </UserHoverCard>
  );
}
