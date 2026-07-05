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

import type { KeyboardEvent } from 'react';
import Box from '@mui/material/Box';
import Grow from '@mui/material/Grow';
import IconButton from '@mui/material/IconButton';
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import FocusTrap from '@mui/material/Unstable_TrapFocus';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import { ChevronDown, ChevronUp, X } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { tokens } from '../../../theme/tokens';
import type { Notify } from '../../admin/layout/useToast';
import { ToneBadge } from '../../admin/ToneBadge';
import { STATUS_CUES } from '../panel/statusCues';
import { CommentThread } from '../panel/CommentThread';
import { ResolveBar } from '../panel/ResolveBar';
import {
  mayReopenAnnotation,
  mayResolveAnnotation,
  useReopenWithFeedback,
  useResolveWithFeedback,
} from '../panel/resolve';
import { PlacementStatusChip } from '../panel/PlacementStatusChip';
import type { WalkPosition } from './spotlightModel';

interface FocusAnnotationCardProps {
  annotation: AnnotationView;
  /** The spotlit mark element the card floats next to. */
  anchorEl: HTMLElement | null;
  /** Position among the placed annotations; null for an unplaced annotation. */
  position: WalkPosition | null;
  onNavigate: (annotationId: string) => void;
  onClose: () => void;
  userId: string | null;
  notify: Notify;
  /** True while an OLDER version is viewed (#306): thread readable, nothing writable. */
  readOnly?: boolean;
  /** True once the review is FINALIZED/CANCELLED (issue #394): no reopening. */
  reviewClosed?: boolean;
  /** The previous visit (issue #307) — enables the thread's "new" divider. */
  previousSeenAt?: string | null;
}

/** True when the key event originates in a text field (arrows must move the caret). */
function isTypingTarget(event: KeyboardEvent): boolean {
  const element = event.target as HTMLElement;
  return element.tagName === 'TEXTAREA' || element.tagName === 'INPUT';
}

/**
 * Focus mode's floating discussion card (issue #291): everything the panel
 * card offers — status and placement cues, the quote, the author's Resolve
 * bar, the full comment thread with its composer — next to the spotlit mark,
 * never over it.
 * Prev/Next (and ↑/↓ outside text fields) walk the annotations in document
 * order without closing; the walk position is announced politely. Focus is
 * trapped inside the card; Escape (and the close button) return it to the
 * mark.
 */
export function FocusAnnotationCard({
  annotation,
  anchorEl,
  position,
  onNavigate,
  onClose,
  userId,
  notify,
  readOnly = false,
  reviewClosed = false,
  previousSeenAt = null,
}: FocusAnnotationCardProps) {
  const theme = useTheme();
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  const { resolveWith, isPending: resolving } = useResolveWithFeedback(notify);
  const { reopenWith } = useReopenWithFeedback(notify);
  const statusCue = STATUS_CUES[annotation.status];
  const quote = annotation.anchor?.textQuote?.quote;

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
      return;
    }
    if (isTypingTarget(event)) return;
    if (event.key === 'ArrowUp' && position?.prevId) {
      event.preventDefault();
      onNavigate(position.prevId);
    }
    if (event.key === 'ArrowDown' && position?.nextId) {
      event.preventDefault();
      onNavigate(position.nextId);
    }
  };

  return (
    <Popper
      open={Boolean(anchorEl)}
      anchorEl={anchorEl}
      placement="right-start"
      transition
      sx={{ zIndex: theme.zIndex.modal }}
      modifiers={[
        { name: 'offset', options: { offset: [0, 16] } },
        { name: 'flip', options: { fallbackPlacements: ['left-start', 'bottom', 'top'] } },
        { name: 'preventOverflow', options: { padding: 12 } },
      ]}
    >
      {({ TransitionProps, placement }) => (
        <Grow
          {...TransitionProps}
          // The staged entrance: scrim first, then the card — collapsed to
          // instant under prefers-reduced-motion.
          timeout={reducedMotion ? 0 : 200}
          style={{
            transitionDelay: reducedMotion ? '0ms' : '120ms',
            transformOrigin: placement.startsWith('left') ? 'right top' : 'left top',
          }}
        >
          <Paper
            variant="outlined"
            data-testid="focus-annotation-card"
            onKeyDown={handleKeyDown}
            sx={{
              display: 'flex',
              position: 'relative',
              borderRadius: '10px',
              // Bordered surface with ONE soft ambient shadow — the brand
              // reads as borders, not elevation stacks (theme.ts); dark mode
              // keeps the hairline edge only (shadows vanish on dark).
              boxShadow: theme.palette.mode === 'light' ? tokens.shadow.lg : 'none',
              // The pointer arrow sits just outside the border.
              overflow: 'visible',
            }}
          >
            {/* The prototype's card↔mark link cues: a status rail on the edge
                facing the reader, and a pointer arrow toward the spotlit mark
                (flips with the Popper placement). */}
            <Box
              aria-hidden
              sx={{
                position: 'absolute',
                left: 0,
                top: 10,
                bottom: 10,
                width: 3,
                borderRadius: '0 3px 3px 0',
                bgcolor: statusCue.color(theme),
              }}
            />
            <Box
              aria-hidden
              data-testid="focus-card-arrow"
              sx={{
                position: 'absolute',
                top: 16,
                width: 0,
                height: 0,
                borderTop: '7px solid transparent',
                borderBottom: '7px solid transparent',
                ...(placement.startsWith('left')
                  ? { right: -8, borderLeft: `8px solid ${statusCue.color(theme)}` }
                  : { left: -8, borderRight: `8px solid ${statusCue.color(theme)}` }),
              }}
            />
            {/* Enforcement stays off: the card coexists with interactive marks
                and the toolbar — clicking them must not yank focus (and the
                window scroll) back into the card. Tab still cycles inside. */}
            <FocusTrap open disableRestoreFocus disableEnforceFocus>
              <Box
                tabIndex={-1}
                data-testid="focus-card-body"
                sx={{
                  position: 'relative',
                  display: 'flex',
                  flexDirection: 'column',
                  outline: 'none',
                  borderRadius: '9px',
                  overflow: 'hidden',
                  // Reader-resizable (long threads, narrow viewports): the
                  // native grip, hard-bounded so the card can neither
                  // collapse below usability nor swallow the document.
                  resize: 'both',
                  width: 380,
                  minWidth: 320,
                  maxWidth: 'min(640px, calc(100vw - 48px))',
                  minHeight: 220,
                  maxHeight: 'min(72vh, 680px)',
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.5}
                  sx={{
                    alignItems: 'center',
                    px: 1.5,
                    py: 1,
                    borderBottom: `1px solid ${theme.palette.divider}`,
                    flexShrink: 0,
                  }}
                >
                  <Typography
                    component="span"
                    aria-live="polite"
                    sx={{
                      fontSize: 10,
                      fontWeight: 600,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: 'text.secondary',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {position
                      ? `Annotation ${position.index + 1} of ${position.count}`
                      : 'Annotation'}
                  </Typography>
                  <Box sx={{ flex: 1 }} />
                  <Tooltip title="Previous annotation (↑)">
                    <span>
                      <IconButton
                        size="small"
                        aria-label="Previous annotation"
                        disabled={!position?.prevId}
                        onClick={() => position?.prevId && onNavigate(position.prevId)}
                      >
                        <ChevronUp size={16} />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title="Next annotation (↓)">
                    <span>
                      <IconButton
                        size="small"
                        aria-label="Next annotation"
                        disabled={!position?.nextId}
                        onClick={() => position?.nextId && onNavigate(position.nextId)}
                      >
                        <ChevronDown size={16} />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <IconButton size="small" aria-label="Close annotation" onClick={onClose}>
                    <X size={16} />
                  </IconButton>
                </Stack>

                <Box sx={{ px: 1.5, pt: 1.25, flexShrink: 0 }}>
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.75 }}>
                    <ToneBadge tone={statusCue.tone} label={statusCue.label} />
                    <PlacementStatusChip status={annotation.placementStatus} />
                  </Stack>
                  {quote && (
                    <Typography
                      variant="body2"
                      color="text.secondary"
                      sx={{
                        fontStyle: 'italic',
                        borderLeft: `2px solid ${theme.palette.divider}`,
                        pl: 1,
                        display: '-webkit-box',
                        WebkitLineClamp: 3,
                        WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                      }}
                    >
                      {`“${quote}”`}
                    </Typography>
                  )}
                  {/* The opening annotation text — the thread below carries
                      only the replies (issue #403). */}
                  {annotation.firstComment && (
                    <Typography
                      variant="body2"
                      sx={{ mt: 0.75, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
                      data-testid="opening-text"
                    >
                      {annotation.firstComment}
                    </Typography>
                  )}
                </Box>

                {!readOnly && mayResolveAnnotation(annotation, userId) && (
                  <ResolveBar
                    disabled={resolving}
                    onResolve={(note) => resolveWith(annotation, note)}
                  />
                )}

                <Box sx={{ overflowY: 'auto', flex: 1, minHeight: 0, px: 0.5, pb: 0.5 }}>
                  <CommentThread
                    annotationId={annotation.id}
                    notify={notify}
                    readOnly={readOnly}
                    closed={annotation.status !== AnnotationStatus.Open}
                    onReopen={
                      !readOnly && !reviewClosed && mayReopenAnnotation(annotation, userId)
                        ? () => reopenWith(annotation)
                        : undefined
                    }
                    previousSeenAt={previousSeenAt}
                    skipOpener
                  />
                </Box>
                {/* Discoverability for the native resize grip underneath. */}
                <Box
                  aria-hidden
                  sx={{
                    position: 'absolute',
                    right: 2,
                    bottom: 2,
                    width: 11,
                    height: 11,
                    pointerEvents: 'none',
                    clipPath: 'polygon(100% 0, 100% 100%, 0 100%)',
                    background: `repeating-linear-gradient(135deg, transparent 0 2.5px, ${theme.palette.divider} 2.5px 4px)`,
                  }}
                />
              </Box>
            </FocusTrap>
          </Paper>
        </Grow>
      )}
    </Popper>
  );
}
