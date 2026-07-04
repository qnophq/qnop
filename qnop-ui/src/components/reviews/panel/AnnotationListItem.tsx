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
import { alpha, keyframes, useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import { ToneBadge } from '../../admin/ToneBadge';
import { UserAvatar } from '../../shell/UserAvatar';
import { highlightColorFor } from '../viewer/markerColors';
import { PlacementStatusChip } from './PlacementStatusChip';
import { STATUS_CUES } from './statusCues';

/** Gentle glow on the status rail while the card is linked to its hovered mark. */
const railGlow = keyframes`
  0%, 100% { box-shadow: 0 0 0 0 transparent; }
  50% { box-shadow: 0 0 10px 2px var(--rail-glow); }
`;

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
 * comment timeline follows below. The left rail always carries the colour the
 * mark paints with on the page, and hovering either side of the card↔mark
 * pair lights up the other.
 *
 * Memoized (issue #333): the panel re-renders on every hover/selection, but with stable props only
 * the two items whose {@code active}/{@code linked} actually changed re-render — not the whole list.
 */
function AnnotationListItemBase({
  annotation,
  active,
  linked = false,
  onSelect,
  onHover,
}: AnnotationListItemProps) {
  const theme = useTheme();
  const quote = annotation.anchor?.textQuote?.quote;
  const region = annotation.anchor?.region;
  const statusCue = STATUS_CUES[annotation.status];
  const StatusIcon = statusCue.icon;
  const railColor = annotation.anchor
    ? highlightColorFor(annotation, theme.palette)
    : theme.palette.divider;

  // Participants: the annotation author plus every commenter already known to
  // the cache (enabled: false never fetches — rows stay cheap; the stack
  // enriches once a thread has been opened or hover-prefetched).
  const cachedComments = useComments(annotation.id, false).data?.comments ?? [];
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
        borderColor: active ? theme.qnop.brand.blue : linked ? railColor : theme.palette.divider,
        bgcolor: active
          ? alpha(theme.qnop.brand.blue, 0.06)
          : linked
            ? alpha(railColor, 0.08)
            : 'transparent',
        pl: 2,
        pr: 1.25,
        py: active ? 1.25 : 0.75,
        transition:
          'border-color 120ms ease, background-color 120ms ease, transform 160ms ease, box-shadow 160ms ease',
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        ...(linked && {
          transform: 'translateX(-3px)',
          boxShadow: `0 8px 22px -10px ${alpha(railColor, 0.8)}`,
        }),
        '&:hover': { borderColor: railColor },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
      }}
    >
      {/* Link arrow pointing at the document while the pair is hot (prototype). */}
      {linked && (
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            left: -7,
            top: '50%',
            transform: 'translateY(-50%)',
            width: 0,
            height: 0,
            borderTop: '6px solid transparent',
            borderBottom: '6px solid transparent',
            borderRight: `7px solid ${railColor}`,
          }}
        />
      )}
      {/* Status rail — the same colour the mark paints with on the page. */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          left: 0,
          top: 8,
          bottom: 8,
          width: 3,
          borderRadius: '0 3px 3px 0',
          bgcolor: railColor,
          opacity: linked || active ? 1 : 0.55,
          transition: 'opacity 120ms ease',
          '--rail-glow': alpha(railColor, 0.55),
          ...(linked && { animation: `${railGlow} 1.1s ease-in-out infinite` }),
          '@media (prefers-reduced-motion: reduce)': { animation: 'none', transition: 'none' },
        }}
      />
      {active ? (
        <Stack spacing={0.75}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <ToneBadge tone={statusCue.tone} label={statusCue.label} />
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
          {quote ? (
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
          ) : (
            <Typography variant="body2" color="text.secondary">
              {fallbackLabel}
            </Typography>
          )}
        </Stack>
      ) : (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
          <Tooltip title={statusCue.label}>
            <Box sx={{ display: 'flex', color: statusCue.color(theme), flexShrink: 0 }}>
              <StatusIcon size={15} aria-label={statusCue.label} />
            </Box>
          </Tooltip>
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
            sx={{ alignItems: 'center', color: 'text.secondary', flexShrink: 0 }}
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
