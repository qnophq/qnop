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
import Fade from '@mui/material/Fade';
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
import type { Notify } from '../../admin/layout/useToast';
import { ToneBadge } from '../../admin/ToneBadge';
import { STATUS_CUES } from '../panel/statusCues';
import { CommentThread } from '../panel/CommentThread';
import { DecisionBar } from '../panel/DecisionBar';
import { mayDecideAnnotation, useDecideWithFeedback } from '../panel/decisions';
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
  ownerId: string | null;
  userId: string | null;
  notify: Notify;
}

/** True when the key event originates in a text field (arrows must move the caret). */
function isTypingTarget(event: KeyboardEvent): boolean {
  const element = event.target as HTMLElement;
  return element.tagName === 'TEXTAREA' || element.tagName === 'INPUT';
}

/**
 * Focus mode's floating discussion card (issue #291): everything the panel
 * card offers — status and placement cues, the quote, Accept/Reject, the full
 * comment thread with its composer — next to the spotlit mark, never over it.
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
  ownerId,
  userId,
  notify,
}: FocusAnnotationCardProps) {
  const theme = useTheme();
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  const { decideWith, isPending: deciding } = useDecideWithFeedback(notify);
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
      {({ TransitionProps }) => (
        <Fade
          {...TransitionProps}
          // The staged entrance: scrim first, then the card — collapsed to
          // instant under prefers-reduced-motion.
          timeout={reducedMotion ? 0 : 180}
          style={{ transitionDelay: reducedMotion ? '0ms' : '120ms' }}
        >
          <Paper
            elevation={8}
            data-testid="focus-annotation-card"
            onKeyDown={handleKeyDown}
            sx={{
              width: 380,
              maxWidth: 'calc(100vw - 32px)',
              maxHeight: 'min(70vh, 560px)',
              display: 'flex',
              flexDirection: 'column',
              borderRadius: 2,
              overflow: 'hidden',
            }}
          >
            {/* Enforcement stays off: the card coexists with interactive marks
                and the toolbar — clicking them must not yank focus (and the
                window scroll) back into the card. Tab still cycles inside. */}
            <FocusTrap open disableRestoreFocus disableEnforceFocus>
              <Box
                tabIndex={-1}
                sx={{ display: 'flex', flexDirection: 'column', minHeight: 0, outline: 'none' }}
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
                    variant="caption"
                    aria-live="polite"
                    sx={{ color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}
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
                        display: '-webkit-box',
                        WebkitLineClamp: 3,
                        WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                      }}
                    >
                      {`“${quote}”`}
                    </Typography>
                  )}
                </Box>

                {mayDecideAnnotation(annotation, userId, ownerId) && (
                  <DecisionBar
                    disabled={deciding}
                    onDecide={(decision) => decideWith(annotation, decision)}
                  />
                )}

                <Box sx={{ overflowY: 'auto', minHeight: 0, px: 0.5, pb: 0.5 }}>
                  <CommentThread annotationId={annotation.id} notify={notify} />
                </Box>
              </Box>
            </FocusTrap>
          </Paper>
        </Fade>
      )}
    </Popper>
  );
}
