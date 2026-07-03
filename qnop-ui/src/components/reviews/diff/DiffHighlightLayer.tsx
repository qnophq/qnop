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
import Box from '@mui/material/Box';
import { alpha, useTheme } from '@mui/material/styles';
import type { DiffChange, RenderedTextSpan } from '../../../api/generated';
import { DiffChangeType } from '../../../api/generated';
import { markerLineBox, surfaceLinePitch } from '../viewer/anchoring';
import type { DiffSide } from './diffModel';
import { CHANGE_KIND, surfaceChangeBoxes } from './diffModel';

interface DiffHighlightLayerProps {
  /** All located changes of the compared pair — the layer picks this surface's. */
  changes: DiffChange[];
  side: DiffSide;
  surfaceIndex: number;
  /** The surface's text spans — grows the bands to the line pitch when present. */
  spans?: RenderedTextSpan[];
  activeChangeIndex: number | null;
  onSelectChange: (changeIndex: number) => void;
}

/**
 * The change overlay of one surface in one compare pane (ADR-0034): every
 * located change paints as multiply-blended marker bands over the rendered
 * original — insertions green, deletions red, in-place changes amber, exactly
 * the annotation-marker look. Geometry comes from the server's located boxes,
 * grown to the surface's line pitch like the annotation highlights. Clicking
 * a band selects its change card in the summary panel (and vice versa — the
 * active change paints deeper).
 */
export function DiffHighlightLayer({
  changes,
  side,
  surfaceIndex,
  spans,
  activeChangeIndex,
  onSelectChange,
}: DiffHighlightLayerProps) {
  const theme = useTheme();
  const entries = surfaceChangeBoxes(changes, side, surfaceIndex);
  if (entries.length === 0) return null;

  const pitch = spans && spans.length > 0 ? surfaceLinePitch(spans) : null;
  const colorFor = (type: DiffChangeType) =>
    type === DiffChangeType.Inserted
      ? theme.palette.success.main
      : type === DiffChangeType.Deleted
        ? theme.palette.error.main
        : theme.palette.warning.main;

  return (
    <Box sx={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
      {entries.map(({ changeIndex, type, boxes }) => {
        const color = colorFor(type);
        const active = changeIndex === activeChangeIndex;
        return (
          <Fragment key={changeIndex}>
            {boxes.map((box, index) => {
              const band = markerLineBox(box, pitch);
              const primary = index === 0;
              return (
                <Box
                  key={index}
                  id={primary ? `diff-change-${side}-${changeIndex}` : undefined}
                  data-testid={primary ? `diff-highlight-${side}-${changeIndex}` : undefined}
                  role={primary ? 'button' : undefined}
                  tabIndex={primary ? 0 : undefined}
                  aria-pressed={primary ? active : undefined}
                  aria-label={primary ? `${CHANGE_KIND[type].label} change` : undefined}
                  aria-hidden={primary ? undefined : true}
                  onClick={() => onSelectChange(changeIndex)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onSelectChange(changeIndex);
                    }
                  }}
                  sx={{
                    position: 'absolute',
                    left: `${band.x * 100}%`,
                    top: `${band.y * 100}%`,
                    width: `${band.width * 100}%`,
                    height: `${band.height * 100}%`,
                    pointerEvents: 'auto',
                    cursor: 'pointer',
                    // Marker look shared with the annotation highlights: a
                    // borderless wash that multiplies over the page pixels;
                    // the active change simply paints deeper.
                    bgcolor: alpha(color, active ? 0.55 : 0.28),
                    mixBlendMode: 'multiply',
                    borderRadius: '1px',
                    transition: 'background-color 120ms ease',
                    '&:hover': { bgcolor: alpha(color, 0.45) },
                    '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
                    '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
                  }}
                />
              );
            })}
          </Fragment>
        );
      })}
    </Box>
  );
}
