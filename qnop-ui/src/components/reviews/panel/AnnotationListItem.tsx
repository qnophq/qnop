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
import { ToneBadge } from '../../admin/ToneBadge';
import { UserAvatar } from '../../shell/UserAvatar';
import { shortRelativeTime } from '../../../utils/relativeTime';
import { hasNewComments, isUnseen } from '../newSince';
import { tokens } from '../../../theme/tokens';
import { PRIORITY_CUES, TYPE_CUES } from '../tasks/tasksModel';
import { PlacementStatusChip } from './PlacementStatusChip';
import { STATUS_CUES } from './statusCues';

/** Compact date for the head card's author line. */
const DATE_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

/** Up to this many participant avatars stack in the collapsed row. */
const MAX_AVATARS = 3;

/** Overlapping avatar stack of everyone involved in the discussion. */
function ParticipantAvatars({ ids }: { ids: string[] }) {
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const shown = ids.slice(0, MAX_AVATARS);
  return (
    <Stack direction="row" sx={{ alignItems: 'center', flexShrink: 0 }}>
      {shown.map((id, index) => {
        const own = id === userId;
        return (
          <Box
            key={id}
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
              name={own ? (displayName ?? 'You') : 'Participant'}
              size={20}
              imageUrl={own ? avatarUrl : null}
            />
          </Box>
        );
      })}
      {ids.length > MAX_AVATARS && (
        <Typography variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>
          +{ids.length - MAX_AVATARS}
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
  const selfDisplayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const userId = viewerId;
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
  const typeCue = annotation.type ? TYPE_CUES[annotation.type] : null;
  const TypeIcon = typeCue?.icon;
  const priorityCue = annotation.priority ? PRIORITY_CUES[annotation.priority] : null;
  // The opening annotation text: the full body once the thread is cached,
  // the server-side excerpt as the placeholder until then.
  const openerText = cachedComments[0]?.body ?? annotation.firstComment ?? null;
  const authorName = annotation.authorId === userId ? (selfDisplayName ?? 'You') : 'Participant';
  const participantIds = [
    ...new Set([annotation.authorId, ...cachedComments.map((comment) => comment.authorId)]),
  ];

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
        // Two quiet steps of the interaction blue: hover tints the surface,
        // the linked mark and the active card share the full accent — the
        // card↔mark pair reads as one hovered thing, no arrow needed.
        borderColor: active || linked ? theme.qnop.brand.blue : theme.palette.divider,
        bgcolor: active || linked ? alpha(theme.qnop.brand.blue, 0.06) : 'transparent',
        boxShadow: linked && theme.palette.mode === 'light' ? tokens.shadow.sm : 'none',
        px: 1.25,
        py: active ? 1.25 : 0.75,
        transition: 'border-color 120ms ease, background-color 120ms ease, box-shadow 120ms ease',
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        '&:hover': {
          borderColor: active ? theme.qnop.brand.blue : alpha(theme.qnop.brand.blue, 0.4),
          bgcolor: active ? alpha(theme.qnop.brand.blue, 0.06) : theme.qnop.surface2,
        },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
      }}
    >
      {active ? (
        <Stack spacing={1} data-testid="annotation-head-card">
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <ToneBadge tone={statusCue.tone} label={statusCue.label} />
            {unseen && <ToneBadge tone="blue" label="New" />}
            {typeCue && TypeIcon && (
              <Stack
                direction="row"
                spacing={0.5}
                sx={{ alignItems: 'center', color: typeCue.color(theme) }}
              >
                <TypeIcon size={12} aria-hidden />
                <Typography component="span" sx={{ fontSize: 11, fontWeight: 600 }}>
                  {typeCue.label}
                </Typography>
              </Stack>
            )}
            {priorityCue && (
              <Tooltip title={`${priorityCue.label} priority`}>
                <Box
                  sx={{
                    width: 7,
                    height: 7,
                    borderRadius: '50%',
                    bgcolor: priorityCue.color(theme),
                    flexShrink: 0,
                  }}
                />
              </Tooltip>
            )}
            <PlacementStatusChip status={annotation.placementStatus} />
            <Stack
              direction="row"
              spacing={1}
              sx={{ alignItems: 'center', ml: 'auto', color: 'text.secondary' }}
            >
              {region && <Typography variant="caption">Page {region.surfaceIndex + 1}</Typography>}
              <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                <MessageSquare size={13} aria-hidden />
                <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
                  {annotation.commentCount}
                </Typography>
              </Stack>
            </Stack>
          </Stack>
          {/* The anchored passage, styled as a real quotation. */}
          {quote ? (
            <Box
              sx={{
                borderLeft: '3px solid',
                borderColor: 'divider',
                bgcolor: theme.qnop.surface2,
                borderRadius: '0 6px 6px 0',
                px: 1.25,
                py: 0.75,
              }}
            >
              <Typography
                variant="body2"
                sx={{
                  fontStyle: 'italic',
                  color: 'text.secondary',
                  display: '-webkit-box',
                  WebkitLineClamp: 4,
                  WebkitBoxOrient: 'vertical',
                  overflow: 'hidden',
                }}
              >
                “{quote}”
              </Typography>
            </Box>
          ) : (
            <Typography variant="body2" color="text.secondary">
              {fallbackLabel}
            </Typography>
          )}
          {/* The opening annotation text — full body once the thread is cached,
              the server's excerpt until then (issue #403). */}
          {openerText && (
            <Typography
              variant="body2"
              sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
              data-testid="opening-text"
            >
              {openerText}
            </Typography>
          )}
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <UserAvatar
              name={authorName}
              size={20}
              imageUrl={annotation.authorId === userId ? avatarUrl : null}
            />
            <Typography
              variant="caption"
              color="text.secondary"
              noWrap
              title={DATE_FORMAT.format(new Date(annotation.createdAt))}
            >
              {authorName} · {shortRelativeTime(annotation.createdAt)}
            </Typography>
          </Stack>
        </Stack>
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
          <ParticipantAvatars ids={participantIds} />
        </Stack>
      )}
    </ButtonBase>
  );
}

export const AnnotationListItem = memo(AnnotationListItemBase);
