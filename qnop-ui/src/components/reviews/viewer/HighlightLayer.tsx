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
import { alpha, useTheme } from '@mui/material/styles';
import type { AnnotationView, NormalizedBox } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';

interface HighlightLayerProps {
  /** All annotations of the document — the layer picks the ones on this surface. */
  annotations: AnnotationView[];
  surfaceIndex: number;
  activeAnnotationId: string | null;
  onSelect: (annotationId: string) => void;
  /** Preview rectangle of a not-yet-created annotation. */
  pendingBox?: NormalizedBox | null;
}

/**
 * The highlight overlay of one surface. Every rectangle is drawn from the
 * stored normalized box of the annotation's placement on the current version
 * (ADR-0032) — the client never computes highlight geometry itself. Colour
 * carries the cue: decided annotations turn green/grey, a placement the
 * re-anchoring engine changed turns amber and dashed, a still-pending
 * placement renders dimmed (ADR-0009).
 */
export function HighlightLayer({
  annotations,
  surfaceIndex,
  activeAnnotationId,
  onSelect,
  pendingBox,
}: HighlightLayerProps) {
  const theme = useTheme();

  const visible = annotations.filter(
    (annotation) => annotation.anchor?.region.surfaceIndex === surfaceIndex,
  );

  const styleFor = (annotation: AnnotationView) => {
    const brand = theme.qnop.brand.blue;
    if (annotation.status === AnnotationStatus.Accepted) {
      return { color: theme.palette.success.main, borderStyle: 'solid', opacity: 1 };
    }
    if (annotation.status === AnnotationStatus.Rejected) {
      return { color: theme.palette.text.disabled, borderStyle: 'solid', opacity: 0.7 };
    }
    switch (annotation.placementStatus) {
      case PlacementStatus.Moved:
        return { color: theme.palette.warning.main, borderStyle: 'dashed', opacity: 1 };
      case PlacementStatus.Pending:
        return { color: brand, borderStyle: 'dotted', opacity: 0.6 };
      default:
        return { color: brand, borderStyle: 'solid', opacity: 1 };
    }
  };

  const handleKeyDown = (event: KeyboardEvent, annotationId: string) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect(annotationId);
    }
  };

  return (
    <Box sx={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
      {visible.map((annotation) => {
        const box = annotation.anchor!.region.box;
        const style = styleFor(annotation);
        const active = annotation.id === activeAnnotationId;
        const quote = annotation.anchor?.textQuote?.quote;
        return (
          <Box
            key={annotation.id}
            id={`annotation-highlight-${annotation.id}`}
            role="button"
            tabIndex={0}
            aria-pressed={active}
            aria-label={quote ? `Annotation: ${quote.slice(0, 60)}` : 'Region annotation'}
            data-testid={`highlight-${annotation.id}`}
            onClick={() => onSelect(annotation.id)}
            onKeyDown={(event) => handleKeyDown(event, annotation.id)}
            sx={{
              position: 'absolute',
              left: `${box.x * 100}%`,
              top: `${box.y * 100}%`,
              width: `${box.width * 100}%`,
              height: `${box.height * 100}%`,
              pointerEvents: 'auto',
              cursor: 'pointer',
              bgcolor: alpha(style.color, active ? 0.28 : 0.16),
              border: `1.5px ${style.borderStyle} ${style.color}`,
              borderRadius: '2px',
              opacity: style.opacity,
              transition: 'background-color 120ms ease',
              '&:hover': { bgcolor: alpha(style.color, 0.26) },
              '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
              ...(active && { boxShadow: theme.qnop.focusRing }),
            }}
          />
        );
      })}
      {pendingBox && (
        <Box
          data-testid="pending-highlight"
          sx={{
            position: 'absolute',
            left: `${pendingBox.x * 100}%`,
            top: `${pendingBox.y * 100}%`,
            width: `${pendingBox.width * 100}%`,
            height: `${pendingBox.height * 100}%`,
            border: `2px dashed ${theme.qnop.brand.blue}`,
            bgcolor: alpha(theme.qnop.brand.blue, 0.08),
            borderRadius: '2px',
            pointerEvents: 'none',
          }}
        />
      )}
    </Box>
  );
}
