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

import { memo } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import { UserAvatar } from '../../shell/UserAvatar';
import { tokens } from '../../../theme/tokens';
import { hasNewComments, isUnseen } from '../newSince';
import { AnnotationHead } from './AnnotationHead';
import { STATUS_CUES } from './statusCues';

/** Up to this many participant avatars stack in the collapsed row. */
const MAX_AVATARS = 3;

interface DiscussionParticipant {
  id: string;
  /** Server-resolved name honouring anonymity (issue #413); real name or "Participant N". */
  name: string | null | undefined;
}

/** Overlapping avatar stack of everyone involved in the discussion. */
function ParticipantAvatars({ participants }: { participants: DiscussionParticipant[] }) {
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const shown = participants.slice(0, MAX_AVATARS);
  return (
    <Stack direction="row" sx={{ alignItems: 'center', flexShrink: 0 }}>
      {shown.map((participant, index) => {
        const own = participant.id === userId;
        return (
          <Box
            key={participant.id}
            sx={{
              borderRadius: '50%',
              border: '2px solid',
              borderColor: 'background.paper',
              ml: index === 0 ? 0 : -0.75,
              display: 'flex',
              zIndex: shown.length - index,
            }}
          >
            <UserAvatar
              name={own ? (displayName ?? 'You') : (participant.name ?? 'Participant')}
              size={20}
              imageUrl={own ? avatarUrl : null}
            />
          </Box>
        );
      })}
      {participants.length > MAX_AVATARS && (
        <Typography variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>
          +{participants.length - MAX_AVATARS}
        </Typography>
      )}
    </Stack>
  );
}

interface AnnotationListItemProps {
  annotation: AnnotationView;
  active: boolean;
  /** The previous visit (issue #307) — null hides every unseen cue. */
  previousSeenAt?: string | null;
  /** True while the mark on the page is hovered — mirrors the link visually. */
  linked?: boolean;
  /**
   * Selects this annotation, or deselects (null) when it is already active. Passed the resolved id
   * so the parent can forward a stable handler — the toggle lives here, keeping the item's props
   * referentially stable for {@link memo} (issue #333).
   */
  onSelect: (annotationId: string | null) => void;
  onHover?: (annotationId: string | null) => void;
}

/**
 * One annotation in the panel. Collapsed it is a single dense row — status
 * icon, the quoted passage, thread size, page and the participants' avatar
 * stack — so a long review stays scannable. The active (clicked) annotation
 * grows into the detailed card with badges, placement cue and full quote; the
 * comment timeline follows below. Colour stays semantic (issue #403): the
 * status speaks through icon, badge and the mark on the page; interaction —
 * hover, the linked mark, the active card — speaks the brand blue in two
 * quiet steps instead of a status rail with a pointer arrow.
 *
 * Memoized (issue #333): the panel re-renders on every hover/selection, but with stable props only
 * the two items whose {@code active}/{@code linked} actually changed re-render — not the whole list.
 */
function AnnotationListItemBase({
  annotation,
  active,
  previousSeenAt = null,
  linked = false,
  onSelect,
  onHover,
}: AnnotationListItemProps) {
  const theme = useTheme();
  const viewerId = useAuthStore((state) => state.userId);
  const unseen = isUnseen(annotation, previousSeenAt, viewerId);
  const freshComments = hasNewComments(annotation, previousSeenAt);
  const quote = annotation.anchor?.textQuote?.quote;
  const region = annotation.anchor?.region;
  const statusCue = STATUS_CUES[annotation.status];
  const StatusIcon = statusCue.icon;

  // Participants: the annotation author plus every commenter already known to
  // the cache (enabled: false never fetches — rows stay cheap; the stack
  // enriches once a thread has been opened or hover-prefetched).
  const cachedComments = useComments(annotation.id, false).data?.comments ?? [];
  // Distinct participants with their server-resolved names (issue #413) — the
  // author, then commenters — so each pseudonym in an anonymous review gets its
  // own distinct avatar rather than a shared "Participant" glyph.
  const participants = [
    { id: annotation.authorId, name: annotation.authorDisplayName },
    ...cachedComments.map((comment) => ({
      id: comment.authorId,
      name: comment.authorDisplayName,
    })),
  ].filter(
    (participant, index, all) => all.findIndex((other) => other.id === participant.id) === index,
  );

  const fallbackLabel = region ? 'Region annotation' : 'No placement on this version';

  return (
    <ButtonBase
      onClick={() => onSelect(active ? null : annotation.id)}
      onMouseEnter={() => onHover?.(annotation.id)}
      onMouseLeave={() => onHover?.(null)}
      onFocus={() => onHover?.(annotation.id)}
      onBlur={() => onHover?.(null)}
      aria-expanded={active}
      data-testid={`annotation-item-${annotation.id}`}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        position: 'relative',
        borderRadius: 0.75,
        border: '1px solid',
        // The frame belongs to the head card alone (#403) — the thread and
        // composer hang off the timeline rail below it, unframed. Two quiet
        // steps of the interaction blue on the edge.
        borderColor: active || linked ? theme.qnop.brand.blue : theme.palette.divider,
        bgcolor: active || linked ? alpha(theme.qnop.brand.blue, 0.06) : 'background.paper',
        boxShadow: linked && theme.palette.mode === 'light' ? tokens.shadow.sm : 'none',
        px: 1.25,
        py: active ? 1.25 : 0.75,
        transition: 'border-color 120ms ease, background-color 120ms ease, box-shadow 120ms ease',
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        '&:hover': {
          borderColor: active || linked ? theme.qnop.brand.blue : alpha(theme.qnop.brand.blue, 0.4),
          bgcolor: active || linked ? alpha(theme.qnop.brand.blue, 0.06) : theme.qnop.surface2,
        },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
      }}
    >
      {active ? (
        <AnnotationHead annotation={annotation} unseen={unseen} />
      ) : (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
          <Tooltip title={statusCue.label}>
            <Box sx={{ display: 'flex', color: statusCue.color(theme), flexShrink: 0 }}>
              <StatusIcon size={15} aria-label={statusCue.label} />
            </Box>
          </Tooltip>
          {unseen && (
            <Tooltip title="New since your last visit">
              <Box
                data-testid="unseen-dot"
                sx={{
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  bgcolor: theme.qnop.brand.blue,
                  flexShrink: 0,
                }}
              />
            </Tooltip>
          )}
          <Typography
            variant="body2"
            noWrap
            sx={{
              flex: 1,
              minWidth: 0,
              color: 'text.secondary',
              fontStyle: quote ? 'italic' : 'normal',
            }}
          >
            {quote ? `“${quote}”` : fallbackLabel}
          </Typography>
          <Stack
            direction="row"
            spacing={0.5}
            data-testid="comment-count"
            sx={{
              alignItems: 'center',
              color: freshComments ? theme.qnop.brand.blue : 'text.secondary',
              fontWeight: freshComments ? 600 : undefined,
              flexShrink: 0,
            }}
          >
            <MessageSquare size={13} aria-hidden />
            <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
              {annotation.commentCount}
            </Typography>
          </Stack>
          {region && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}
            >
              p. {region.surfaceIndex + 1}
            </Typography>
          )}
          <ParticipantAvatars participants={participants} />
        </Stack>
      )}
    </ButtonBase>
  );
}

export const AnnotationListItem = memo(AnnotationListItemBase);
