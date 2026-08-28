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

import { memo, useEffect, useRef } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme, type Theme } from '@mui/material/styles';
import { ChevronUp, MessageSquare, MoveRight, Unlink } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { PlacementStatus } from '../../../api/generated';
import { useComments } from '../../../api/hooks/useComments';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { useDocument } from '../../../api/hooks/useDocuments';
import { realAuthorId } from '../../people/realAuthorId';
import { UserHoverCard } from '../../people/UserHoverCard';
import { useMentionNames } from '../markdown/useMentionNames';
import { useReviewDocumentId } from '../reviewDocumentId';
import { UserAvatar } from '../../shell/UserAvatar';
import { avatarSrc } from '../../../utils/avatarUrl';
import { isDocumentScoped } from '../annotationScope';
import { WholeDocumentChip } from '../WholeDocumentChip';
import { tokens } from '../../../theme/tokens';
import { useFormatters } from '../../../hooks/useFormatters';
import { hasNewComments, isUnseen } from '../newSince';
import { PRIORITY_CUES, TYPE_CUES } from '../tasks/tasksModel';
import { AnnotationHead } from './AnnotationHead';
import { STATUS_CUES } from './statusCues';

/** Up to this many participant avatars stack in the collapsed row. */
const MAX_AVATARS = 3;

/** Status tile + gap — the meta line indents to align under the title. */
const TILE_INDENT = '34px';

/**
 * One-line plain-text excerpt of a Markdown body (issue #403): a collapsed
 * document-scoped annotation shows what it SAYS instead of a generic "Whole
 * document" label, so four general remarks stop reading identically. Cheap
 * marker stripping only — the full body renders properly once expanded.
 */
function plainExcerpt(markdown: string): string {
  return markdown
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^[#>\-*\d.\s]+/gm, '')
    .replace(/[`*_~]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

interface DiscussionParticipant {
  id: string;
  /** Server-resolved name honouring anonymity (issue #413); real name or "Participant N". */
  name: string | null | undefined;
  slug?: string | null;
}

/** Overlapping avatar stack of everyone involved in the discussion. */
function ParticipantAvatars({
  participants,
  review,
}: {
  participants: DiscussionParticipant[];
  /** The review's anonymity context — gates the hover cards (issue #482). */
  review: { anonymous?: boolean; ownerId?: string } | undefined;
}) {
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const shown = participants.slice(0, MAX_AVATARS);
  return (
    <Stack direction="row" sx={{ alignItems: 'center', flexShrink: 0 }}>
      {shown.map((participant, index) => {
        const own = participant.id === userId;
        return (
          <UserHoverCard
            key={participant.id}
            userId={realAuthorId(review, userId, participant.id)}
            slug={participant.slug}
            profileName={participant.name ?? undefined}
            // The stack lives inside the collapsed row's toggle button, so the
            // triggers stay previews rather than links (issue #549) — a link
            // inside a button is invalid, and these 20px avatars were never the
            // way to a profile. The card still previews on hover; the expanded
            // card's author row carries the real link.
            link={false}
          >
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
              <UserAvatar
                name={own ? (displayName ?? 'You') : (participant.name ?? 'Participant')}
                size={20}
                imageUrl={own ? avatarUrl : avatarSrc(participant.id)}
              />
            </Box>
          </UserHoverCard>
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
  /** The annotation permalink (issue #412) — shown in the expanded head's author row. */
  permalinkUrl?: string;
  notify?: Notify;
  /** Confirms a reviewed MOVED placement (issue #326) — threaded to the head's badge row. */
  onConfirmPlacement?: () => void;
  /** Arms re-attaching a lost placement (issue #457). */
  onReattachPlacement?: () => void;
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
 * The card itself is never a button (issue #549): collapsed, the whole row is
 * one — a single dense target that expands on click or Enter; expanded, it is a
 * plain container whose controls are real controls, closed again by the chevron
 * beside the head. Focus follows that toggle in both directions.
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
  permalinkUrl,
  notify,
  onConfirmPlacement,
  onReattachPlacement,
}: AnnotationListItemProps) {
  const theme = useTheme();
  const { shortRelativeTime, formatDateTime } = useFormatters();
  const viewerId = useAuthStore((state) => state.userId);
  const unseen = isUnseen(annotation, previousSeenAt, viewerId);
  const freshComments = hasNewComments(annotation, previousSeenAt);
  const quote = annotation.anchor?.textQuote?.quote;
  const region = annotation.anchor?.region;
  const statusCue = STATUS_CUES[annotation.status];
  const StatusIcon = statusCue.icon;
  const typeCue = annotation.type ? TYPE_CUES[annotation.type] : null;
  // Re-anchoring outcome worth a glance (ADR-0009, issue #326): amber for a
  // relocated highlight, red for one the resolver lost.
  const placementCue =
    annotation.placementStatus === PlacementStatus.Moved
      ? { icon: MoveRight, label: 'Moved', color: (t: Theme) => t.palette.warning.main }
      : annotation.placementStatus === PlacementStatus.Orphaned ||
          annotation.placementStatus === PlacementStatus.Failed
        ? { icon: Unlink, label: 'Orphaned', color: (t: Theme) => t.palette.error.main }
        : null;
  const priorityCue = annotation.priority ? PRIORITY_CUES[annotation.priority] : null;
  const viewerAvatarUrl = useAuthStore((state) => state.avatarUrl);
  const viewerName = useAuthStore((state) => state.displayName);
  const documentId = useReviewDocumentId();
  // Hover-card anonymity gate (issue #482): read from the cached document.
  const review = useDocument(documentId).data;
  // The collapsed excerpt is plain text — mention tokens read as names there too (issue #462).
  const resolveMentions = useMentionNames(documentId);
  const hoverUserId = realAuthorId(review, viewerId, annotation.authorId);
  const authorName =
    annotation.authorId === viewerId
      ? (viewerName ?? 'You')
      : (annotation.authorDisplayName ?? 'Participant');

  // Participants: the annotation author plus every commenter already known to
  // the cache (enabled: false never fetches — rows stay cheap; the stack
  // enriches once a thread has been opened or hover-prefetched).
  const cachedComments = useComments(annotation.id, false).data?.comments ?? [];
  // Distinct participants with their server-resolved names (issue #413) — the
  // author, then commenters — so each pseudonym in an anonymous review gets its
  // own distinct avatar rather than a shared "Participant" glyph.
  const participants = [
    { id: annotation.authorId, name: annotation.authorDisplayName, slug: annotation.authorSlug },
    ...cachedComments.map((comment) => ({
      id: comment.authorId,
      name: comment.authorDisplayName,
      slug: comment.authorSlug,
    })),
  ].filter(
    (participant, index, all) => all.findIndex((other) => other.id === participant.id) === index,
  );

  // No region means no placement at all → a document-scoped annotation (issue #395); an orphaned
  // annotation keeps its anchor, so it never lands here.
  const fallbackLabel = region ? 'Region annotation' : 'Whole document';

  // Expanding swaps the row's button for the card's collapse control (and back
  // again), so the element that had focus disappears mid-interaction. Focus
  // therefore follows the toggle — but only when the reviewer pressed it HERE:
  // a mark click on the page selects the row too (issue #491) and must not
  // yank focus out of the document (issue #549).
  const toggledHere = useRef(false);
  const rowRef = useRef<HTMLButtonElement>(null);
  const collapseRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!toggledHere.current) return;
    toggledHere.current = false;
    (active ? collapseRef : rowRef).current?.focus();
  }, [active]);
  const toggle = () => {
    toggledHere.current = true;
    onSelect(active ? null : annotation.id);
  };

  return (
    <Box
      // The card is a plain container (issue #549). Expanded it hosts real
      // controls — placement actions, copy, reactions, profile links — and
      // interactive content inside a role="button" is invalid ARIA: a screen
      // reader announces the whole card as one button and every nested control
      // depends on stopping propagation. So the toggle is an explicit control
      // instead: collapsed, the entire row IS the button; expanded, the card
      // carries a collapse chevron. That also retires #480's
      // interactive-descendant guard and #478's selection guard — nothing on
      // the expanded card listens for a click any more, so selecting the
      // quoted passage simply selects it.
      onMouseEnter={() => onHover?.(annotation.id)}
      onMouseLeave={() => onHover?.(null)}
      onFocus={() => onHover?.(annotation.id)}
      onBlur={() => onHover?.(null)}
      // Stable DOM id — the scroll target when a mark click selects this row (#491).
      id={`annotation-item-${annotation.id}`}
      data-testid={`annotation-item-${annotation.id}`}
      sx={{
        position: 'relative',
        borderRadius: 0.75,
        border: '1px solid',
        // The frame belongs to the head card alone (#403) — the thread and
        // composer hang off the timeline rail below it, unframed. Two quiet
        // steps of the interaction blue on the edge.
        borderColor: active || linked ? theme.qnop.brand.blue : theme.palette.divider,
        bgcolor: active || linked ? alpha(theme.qnop.brand.blue, 0.06) : 'background.paper',
        boxShadow: linked && theme.palette.mode === 'light' ? tokens.shadow.sm : 'none',
        // Collapsed, the padding belongs to the button so the whole padded row
        // is the click target; expanded, the container owns it.
        ...(active ? { px: 1.25, py: 1.25 } : null),
        transition: 'border-color 120ms ease, background-color 120ms ease, box-shadow 120ms ease',
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        '&:hover': {
          borderColor: active || linked ? theme.qnop.brand.blue : alpha(theme.qnop.brand.blue, 0.4),
          bgcolor: active || linked ? alpha(theme.qnop.brand.blue, 0.06) : theme.qnop.surface2,
        },
      }}
    >
      {active ? (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'flex-start', minWidth: 0 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <AnnotationHead
              annotation={annotation}
              unseen={unseen}
              permalinkUrl={permalinkUrl}
              notify={notify}
              onConfirmPlacement={onConfirmPlacement}
              onReattachPlacement={onReattachPlacement}
            />
          </Box>
          <Tooltip title="Collapse">
            <IconButton
              ref={collapseRef}
              size="small"
              aria-label="Collapse annotation"
              aria-expanded
              aria-controls={`annotation-item-${annotation.id}`}
              onClick={toggle}
              data-testid={`annotation-toggle-${annotation.id}`}
              sx={{
                color: 'text.secondary',
                flexShrink: 0,
                mt: '-2px',
                '&:focus-visible': { boxShadow: theme.qnop.focusRing },
              }}
            >
              <ChevronUp size={16} aria-hidden />
            </IconButton>
          </Tooltip>
        </Stack>
      ) : (
        <ButtonBase
          ref={rowRef}
          onClick={toggle}
          aria-expanded={false}
          aria-controls={`annotation-item-${annotation.id}`}
          data-testid={`annotation-toggle-${annotation.id}`}
          sx={{
            display: 'block',
            width: '100%',
            textAlign: 'left',
            borderRadius: 'inherit',
            px: 1.25,
            py: 0.75,
            '&:focus-visible': { boxShadow: theme.qnop.focusRing },
          }}
        >
          <Stack spacing={0.5} sx={{ minWidth: 0 }}>
            {/* Title line: status tile, the content itself, participants. */}
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
              <Tooltip title={statusCue.label}>
                <Box
                  sx={{
                    position: 'relative',
                    width: 26,
                    height: 26,
                    borderRadius: '8px',
                    display: 'grid',
                    placeItems: 'center',
                    bgcolor: alpha(statusCue.color(theme), 0.12),
                    color: statusCue.color(theme),
                    flexShrink: 0,
                  }}
                >
                  <StatusIcon size={14} aria-label={statusCue.label} />
                  {unseen && (
                    <Tooltip title="New since your last visit">
                      <Box
                        data-testid="unseen-dot"
                        sx={{
                          position: 'absolute',
                          top: -3,
                          right: -3,
                          width: 9,
                          height: 9,
                          borderRadius: '50%',
                          bgcolor: theme.qnop.brand.blue,
                          border: '2px solid',
                          borderColor: 'background.paper',
                        }}
                      />
                    </Tooltip>
                  )}
                </Box>
              </Tooltip>
              <Typography
                variant="body2"
                noWrap
                sx={{
                  flex: 1,
                  minWidth: 0,
                  color: annotation.status === 'RESOLVED' ? 'text.secondary' : 'text.primary',
                  fontStyle: quote ? 'italic' : 'normal',
                }}
              >
                {quote
                  ? `“${quote}”`
                  : plainExcerpt(resolveMentions(annotation.firstComment ?? '')) || fallbackLabel}
              </Typography>
              <ParticipantAvatars participants={participants} review={review} />
            </Stack>
            {/* Meta line: who, when, what kind — then the counters. */}
            <Stack
              direction="row"
              spacing={0.75}
              sx={{ alignItems: 'center', minWidth: 0, pl: TILE_INDENT, color: 'text.secondary' }}
            >
              <UserHoverCard
                userId={hoverUserId}
                slug={annotation.authorSlug}
                profileName={authorName}
                // Preview, not link — see the participant stack above (#549).
                link={false}
                sx={{ alignItems: 'center', gap: 0.75, flexShrink: 1 }}
              >
                <UserAvatar
                  name={authorName}
                  size={16}
                  imageUrl={
                    annotation.authorId === viewerId
                      ? viewerAvatarUrl
                      : avatarSrc(annotation.authorId)
                  }
                />
                <Typography variant="caption" noWrap sx={{ fontWeight: 500, flexShrink: 1 }}>
                  {authorName}
                </Typography>
              </UserHoverCard>
              <Typography
                variant="caption"
                title={formatDateTime(annotation.createdAt)}
                sx={{ flexShrink: 0, color: 'text.disabled' }}
              >
                {shortRelativeTime(annotation.createdAt)}
              </Typography>
              {placementCue && (
                <Stack
                  direction="row"
                  spacing={0.4}
                  sx={{ alignItems: 'center', color: placementCue.color(theme), flexShrink: 0 }}
                >
                  <placementCue.icon size={11} aria-hidden />
                  <Typography component="span" sx={{ fontSize: 11, fontWeight: 700 }}>
                    {placementCue.label}
                  </Typography>
                </Stack>
              )}
              {typeCue && (
                <Stack
                  direction="row"
                  spacing={0.4}
                  sx={{ alignItems: 'center', color: typeCue.color(theme), flexShrink: 0 }}
                >
                  <typeCue.icon size={11} aria-hidden />
                  <Typography variant="caption">{typeCue.label}</Typography>
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
              <Box sx={{ flex: 1 }} />
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
                <MessageSquare size={12} aria-hidden />
                <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
                  {annotation.commentCount}
                </Typography>
              </Stack>
              {region ? (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}
                >
                  p. {region.surfaceIndex + 1}
                </Typography>
              ) : (
                // In the flat list (issue #481) the scope must read per card —
                // collapsed document-scoped rows mark themselves like the
                // anchored ones mark their page.
                isDocumentScoped(annotation) && <WholeDocumentChip compact />
              )}
            </Stack>
          </Stack>
        </ButtonBase>
      )}
    </Box>
  );
}

export const AnnotationListItem = memo(AnnotationListItemBase);
