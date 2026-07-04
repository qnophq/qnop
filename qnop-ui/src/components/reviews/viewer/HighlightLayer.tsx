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

import { Fragment } from 'react';
import type { KeyboardEvent } from 'react';
import Box from '@mui/material/Box';
import { alpha, keyframes, useTheme } from '@mui/material/styles';
import type {
  Anchor,
  AnnotationView,
  NormalizedBox,
  RenderedTextSpan,
} from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { highlightBoxesForAnchor } from './anchoring';
import { SELECTION_MARKER_BG, highlightColorFor } from './markerColors';

/** An annotation whose optional {@link Anchor} is known to be present. */
type AnchoredAnnotation = AnnotationView & { anchor: Anchor };

interface HighlightLayerProps {
  /** All annotations of the document — the layer picks the ones on this surface. */
  annotations: AnnotationView[];
  surfaceIndex: number;
  /** The surface's extracted text spans — text anchors repaint as per-line markers. */
  spans: RenderedTextSpan[];
  activeAnnotationId: string | null;
  /** The annotation hovered anywhere (page or panel) — paints "hot" for linking. */
  hoverAnnotationId?: string | null;
  onSelect: (annotationId: string) => void;
  onHover?: (annotationId: string | null) => void;
  /** The drawn-but-not-yet-created anchor; painted as a preview. */
  pendingAnchor?: Anchor | null;
}

function positionSx(box: NormalizedBox) {
  return {
    position: 'absolute',
    left: `${box.x * 100}%`,
    top: `${box.y * 100}%`,
    width: `${box.width * 100}%`,
    height: `${box.height * 100}%`,
  } as const;
}

/**
 * The card↔mark link cue (prototype `hlHotPulse`): the hovered mark breathes
 * between its rest and hot intensity. Driven by a CSS variable so one
 * keyframe set serves every status colour.
 */
const markPulse = keyframes`
  0%, 100% {
    background-color: color-mix(in srgb, var(--hl) 60%, transparent);
    filter: none;
  }
  50% {
    background-color: var(--hl);
    filter: brightness(0.88) saturate(1.35);
  }
`;

/**
 * The highlight overlay of one surface. Geometry comes from the stored anchors
 * (ADR-0032 — the client never computes highlight geometry from its own
 * rendering): a text anchor paints as per-line marker bands, like a
 * highlighter pen; a region anchor stays a bordered box. Colour carries the
 * cue: decided annotations turn green/grey, a placement the re-anchoring
 * engine changed turns amber, a still-pending placement renders dimmed
 * (ADR-0009).
 */
export function HighlightLayer({
  annotations,
  surfaceIndex,
  spans,
  activeAnnotationId,
  hoverAnnotationId,
  onSelect,
  onHover,
  pendingAnchor,
}: HighlightLayerProps) {
  const theme = useTheme();

  // The predicate narrows the survivors to a guaranteed-anchored shape, so the
  // render below reads `annotation.anchor` without a non-null assertion.
  const visible = annotations.filter(
    (annotation): annotation is AnchoredAnnotation =>
      annotation.anchor?.region.surfaceIndex === surfaceIndex,
  );
  const pending =
    pendingAnchor && pendingAnchor.region.surfaceIndex === surfaceIndex
      ? highlightBoxesForAnchor(pendingAnchor, spans)
      : null;

  /**
   * The mark's colour: open marks paint highlighter yellow — text as per-line
   * bands, regions as one borderless filled box. Cue colours override the
   * base — decided annotations turn green/grey, a MOVED placement turns
   * amber, a still-pending placement renders dimmed.
   */
  const styleFor = (annotation: AnnotationView) => ({
    color: highlightColorFor(annotation, theme.palette),
    opacity:
      annotation.status === AnnotationStatus.Rejected
        ? 0.7
        : annotation.placementStatus === PlacementStatus.Pending
          ? 0.6
          : 1,
  });

  const handleKeyDown = (event: KeyboardEvent, annotationId: string) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect(annotationId);
    }
  };

  return (
    <Box sx={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
      {visible.map((annotation) => {
        const anchor = annotation.anchor;
        const { kind, boxes } = highlightBoxesForAnchor(anchor, spans);
        const style = styleFor(annotation);
        const active = annotation.id === activeAnnotationId;
        const hot = annotation.id === hoverAnnotationId;
        const quote = anchor.textQuote?.quote;
        return (
          <Fragment key={annotation.id}>
            {boxes.map((box, index) => {
              const primary = index === 0;
              const marker = kind === 'marker';
              return (
                <Box
                  key={index}
                  id={primary ? `annotation-highlight-${annotation.id}` : undefined}
                  role={primary ? 'button' : undefined}
                  tabIndex={primary ? 0 : undefined}
                  aria-pressed={primary ? active : undefined}
                  aria-label={
                    primary
                      ? quote
                        ? `Annotation: ${quote.slice(0, 60)}`
                        : 'Region annotation'
                      : undefined
                  }
                  aria-hidden={primary ? undefined : true}
                  data-testid={primary ? `highlight-${annotation.id}` : undefined}
                  onClick={() => onSelect(annotation.id)}
                  onMouseEnter={() => onHover?.(annotation.id)}
                  onMouseLeave={() => onHover?.(null)}
                  onKeyDown={
                    primary
                      ? (event: KeyboardEvent) => handleKeyDown(event, annotation.id)
                      : undefined
                  }
                  sx={{
                    ...positionSx(box),
                    '--hl': style.color,
                    pointerEvents: 'auto',
                    cursor: 'pointer',
                    opacity: style.opacity,
                    transition: 'background-color 120ms ease, box-shadow 120ms ease',
                    // Highlighter look for both kinds: a borderless fill that
                    // multiplies over the page pixels, so printed glyphs stay
                    // crisp underneath. At rest the marker is a pale wash;
                    // hovering the mark itself deepens it gently (no ring —
                    // earlier feedback), while hovering its PANEL CARD stages
                    // the full spotlight: a deep colour pulse plus a glow ring
                    // so the linked passage is unmissable.
                    bgcolor: alpha(style.color, hot ? 0.9 : active ? 0.6 : 0.3),
                    mixBlendMode: 'multiply',
                    borderRadius: marker ? '1px' : '2px',
                    '&:hover': { bgcolor: alpha(style.color, 0.5) },
                    // No ring on selection either — the deeper fill carries the
                    // active state; the ring stays keyboard-focus only.
                    '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
                    // Spotlight without any ring: the linked mark pulses to
                    // the full, brightness-deepened colour instead.
                    ...(hot && {
                      animation: `${markPulse} 0.9s ease-in-out infinite`,
                      zIndex: 1,
                    }),
                    '@media (prefers-reduced-motion: reduce)': {
                      transition: 'none',
                      animation: 'none',
                    },
                  }}
                />
              );
            })}
          </Fragment>
        );
      })}
      {pending &&
        pending.boxes.map((box, index) => (
          <Box
            key={index}
            data-testid={index === 0 ? 'pending-highlight' : undefined}
            sx={{
              ...positionSx(box),
              pointerEvents: 'none',
              ...(pending.kind === 'marker'
                ? {
                    // Identical to the live selection, so releasing the mouse
                    // does not visually change the mark.
                    bgcolor: SELECTION_MARKER_BG,
                    mixBlendMode: 'multiply',
                    borderRadius: '1px',
                  }
                : {
                    bgcolor: SELECTION_MARKER_BG,
                    mixBlendMode: 'multiply',
                    borderRadius: '2px',
                  }),
            }}
          />
        ))}
    </Box>
  );
}
