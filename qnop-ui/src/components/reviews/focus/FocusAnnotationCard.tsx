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

import { useRef, useState } from 'react';
import type { KeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
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
import { AnnotationHead } from '../panel/AnnotationHead';
import { CommentThread } from '../panel/CommentThread';
import { ResolveBar } from '../panel/ResolveBar';
import {
  mayReopenAnnotation,
  mayResolveAnnotation,
  useReopenWithFeedback,
  useResolveWithFeedback,
} from '../panel/resolve';
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
 * Focus mode's floating discussion card (issue #291): the panel's expanded
 * unit as a floating card — the shared AnnotationHead, the author's Resolve
 * bar, the full comment thread with its composer — next to the spotlit mark,
 * never over it (issue #403: same anatomy as the document view, no status
 * rail, no pointer arrow).
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

  // The card is draggable by its header (issue #403): the offset rides on
  // top of the Popper's anchor position and resets when the walk moves to
  // another annotation (the card snaps to the new mark, as expected).
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const dragStart = useRef({ pointerX: 0, pointerY: 0, x: 0, y: 0 });
  const draggingRef = useRef(false);
  const [lastAnnotationId, setLastAnnotationId] = useState(annotation.id);
  if (annotation.id !== lastAnnotationId) {
    setLastAnnotationId(annotation.id);
    setDragOffset({ x: 0, y: 0 });
  }

  const handleDragStart = (event: ReactPointerEvent<HTMLDivElement>) => {
    // The header's buttons keep their clicks; only the free area drags.
    if (event.button !== 0 || (event.target as HTMLElement).closest('button')) return;
    event.preventDefault();
    dragStart.current = {
      pointerX: event.clientX,
      pointerY: event.clientY,
      x: dragOffset.x,
      y: dragOffset.y,
    };
    draggingRef.current = true;
    setDragging(true);
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Synthetic pointer events (tests) have no active pointer to capture.
    }
  };

  const handleDragMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return;
    setDragOffset({
      x: dragStart.current.x + (event.clientX - dragStart.current.pointerX),
      y: dragStart.current.y + (event.clientY - dragStart.current.pointerY),
    });
  };

  const handleDragEnd = () => {
    draggingRef.current = false;
    setDragging(false);
  };

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
            style={{ translate: `${dragOffset.x}px ${dragOffset.y}px` }}
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
                  // Tall threads scroll inside; the card itself stays a
                  // modest share of the screen (issue #403).
                  maxHeight: 'min(55vh, 520px)',
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.5}
                  data-testid="focus-card-handle"
                  onPointerDown={handleDragStart}
                  onPointerMove={handleDragMove}
                  onPointerUp={handleDragEnd}
                  onPointerCancel={handleDragEnd}
                  sx={{
                    alignItems: 'center',
                    px: 1.5,
                    py: 1,
                    borderBottom: `1px solid ${theme.palette.divider}`,
                    flexShrink: 0,
                    cursor: dragging ? 'grabbing' : 'grab',
                    touchAction: 'none',
                    userSelect: 'none',
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
                  {/* The same root post the panel's expanded card shows. */}
                  <AnnotationHead annotation={annotation} />
                </Box>

                {!readOnly && mayResolveAnnotation(annotation, userId) && (
                  // The bar's flush-right button is the panel's look; inside
                  // the floating card it breathes like the left side does.
                  <Box sx={{ pr: 2 }}>
                    <ResolveBar
                      disabled={resolving}
                      onResolve={(note) => resolveWith(annotation, note)}
                    />
                  </Box>
                )}

                {/* Same breathing room at the bottom as the head keeps at the top. */}
                <Box sx={{ overflowY: 'auto', flex: 1, minHeight: 0, pl: 0.5, pr: 2, pb: 1.25 }}>
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
