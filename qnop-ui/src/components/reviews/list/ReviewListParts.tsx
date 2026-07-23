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
import type { Theme } from '@mui/material/styles';
import { Eye, User } from 'lucide-react';
import type { ParticipantView } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';
import { UserHoverCard } from '../../people/UserHoverCard';
import { UserAvatar } from '../../shell/UserAvatar';
import { TeamAvatar } from '../../shell/TeamAvatar';
import { avatarSrc, teamAvatarSrc } from '../../../utils/avatarUrl';
import { tokens } from '../../../theme/tokens';

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

/** Known document MIME types and their ribbon label + tone (issue #509 follow-up). */
const DOC_TYPE_META: Record<string, { label: string; tone: 'red' | 'blue' | 'neutral' }> = {
  'application/pdf': { label: 'PDF', tone: 'red' },
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': {
    label: 'DOCX',
    tone: 'blue',
  },
  'application/msword': { label: 'DOC', tone: 'blue' },
  'text/markdown': { label: 'MD', tone: 'neutral' },
};

/** Ribbon fill/text plus the dog-ear tint for one format family. */
function ribbonColorsFor(tone: 'red' | 'blue' | 'neutral', theme: Theme) {
  if (tone === 'neutral') {
    const fill = theme.palette.grey[theme.palette.mode === 'dark' ? 400 : 600];
    return { fill, text: theme.palette.getContrastText(fill), tint: theme.qnop.surface2 };
  }
  const badge = theme.qnop.badge[tone];
  const fill = theme.palette.mode === 'dark' ? badge.fgDark : badge.fg;
  return { fill, text: theme.palette.getContrastText(fill), tint: badge.bg };
}

/**
 * The document sheet leading every row/card, drawn as a real file icon: a
 * page with a tone-tinted dog-ear fold, faint text lines, and — for known
 * MIME types — a format ribbon that wraps the left edge (PDF red, Word blue,
 * Markdown neutral). Unknown types keep the plain sheet, so future formats
 * degrade gracefully (ADR-0010).
 */
export function DocumentIcon({
  size = 30,
  contentType,
}: {
  size?: number;
  contentType?: string | null;
}) {
  const theme = useTheme();
  // MIME parameters (e.g. "; charset=utf-8") never change the format family.
  const normalized = contentType?.split(';')[0].trim().toLowerCase() ?? '';
  const meta = DOC_TYPE_META[normalized];
  const ribbon = meta ? ribbonColorsFor(meta.tone, theme) : null;
  const sheetFill =
    theme.palette.mode === 'dark' ? theme.qnop.surface2 : theme.palette.background.paper;
  const stroke = theme.palette.divider;
  // The ribbon covers the lower band, so the plain sheet gets the longer ramp.
  const textLines: Array<[number, number]> = meta
    ? [
        [17.5, 17],
        [22.5, 12],
      ]
    : [
        [17.5, 17],
        [22.5, 12],
        [27.5, 18],
        [32.5, 9],
      ];
  const sheet = (
    <Box
      component="svg"
      viewBox="0 0 40 48"
      role={meta ? 'img' : undefined}
      aria-label={meta ? `${meta.label} document` : undefined}
      aria-hidden={!meta}
      data-testid="document-icon"
      sx={{ width: size, height: size * 1.2, display: 'block', flexShrink: 0 }}
    >
      {/* Page with the top-right corner cut for the fold. */}
      <path
        d="M8 2h17l11 11v29a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V6a4 4 0 0 1 4-4Z"
        fill={sheetFill}
        stroke={stroke}
        strokeWidth="1.5"
      />
      {/* Dog-ear flap, tinted in the format tone. */}
      <path
        d="M25 2l11 11h-7a4 4 0 0 1-4-4Z"
        fill={ribbon ? ribbon.tint : theme.qnop.surface2}
        stroke={stroke}
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      {textLines.map(([y, width]) => (
        <rect key={y} x="9" y={y} width={width} height="2.5" rx="1.25" fill={stroke} />
      ))}
      {meta && ribbon && (
        <>
          {/* Fold-back under the overhang — the depth cue of a wrapped ribbon. */}
          <path d="M1 41l3 3v-3Z" fill={ribbon.fill} />
          <path d="M1 41l3 3v-3Z" fill="#000" opacity="0.35" />
          <rect x="1" y="29" width="31" height="12" rx="3" fill={ribbon.fill} />
          <text
            x="16.5"
            y="35"
            textAnchor="middle"
            dominantBaseline="central"
            fontFamily={tokens.font.mono}
            fontWeight="700"
            fontSize={meta.label.length > 3 ? 8 : 9.5}
            letterSpacing="0.5"
            fill={ribbon.text}
          >
            {meta.label}
          </text>
        </>
      )}
    </Box>
  );
  return meta ? <Tooltip title={`${meta.label} document`}>{sheet}</Tooltip> : sheet;
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
