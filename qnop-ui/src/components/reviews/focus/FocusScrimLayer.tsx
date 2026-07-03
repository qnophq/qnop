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

import Box from '@mui/material/Box';
import type { NormalizedBox } from '../../../api/generated';

interface FocusScrimLayerProps {
  /** The sharp region when the spotlight targets THIS page; null dims it fully. */
  spotlight: NormalizedBox | null;
  /** Clicking the dimmed area closes the overlay. */
  onDismiss: () => void;
  surfaceIndex: number;
}

/** The dimmed segments around the spotlight (or one full cover without one). */
function segments(spotlight: NormalizedBox | null): NormalizedBox[] {
  if (!spotlight) return [{ x: 0, y: 0, width: 1, height: 1 }];
  const { x, y, width, height } = spotlight;
  return [
    { x: 0, y: 0, width: 1, height: y }, // above
    { x: 0, y: y + height, width: 1, height: Math.max(0, 1 - y - height) }, // below
    { x: 0, y, width: x, height }, // left of the spotlight
    { x: x + width, y, width: Math.max(0, 1 - x - width), height }, // right of it
  ].filter((segment) => segment.width > 0 && segment.height > 0);
}

/**
 * The focus mode's per-page scrim (issue #291): the page dims under a soft
 * veil while the spotlit passage stays untouched — the scrim is four
 * rectangles AROUND the spotlight, never a filter over it, so the anchored
 * text keeps its full contrast (the marks themselves paint on the layer
 * above). A light backdrop blur adds atmosphere; `prefers-reduced-
 * transparency` falls back to the plain veil, and the fade respects
 * `prefers-reduced-motion`. Clicking the veil dismisses the overlay.
 */
export function FocusScrimLayer({ spotlight, onDismiss, surfaceIndex }: FocusScrimLayerProps) {
  return (
    <Box
      data-testid={`focus-scrim-${surfaceIndex}`}
      sx={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
    >
      {segments(spotlight).map((segment, index) => (
        <Box
          key={index}
          data-testid={spotlight ? `scrim-segment-${surfaceIndex}-${index}` : undefined}
          onClick={onDismiss}
          sx={{
            position: 'absolute',
            left: `${segment.x * 100}%`,
            top: `${segment.y * 100}%`,
            width: `${segment.width * 100}%`,
            height: `${segment.height * 100}%`,
            pointerEvents: 'auto',
            cursor: 'pointer',
            bgcolor: 'rgba(1, 32, 66, 0.32)',
            backdropFilter: 'blur(1.5px)',
            '@media (prefers-reduced-transparency: reduce)': { backdropFilter: 'none' },
            animation: 'qnopScrimIn 200ms ease-out',
            '@keyframes qnopScrimIn': { from: { opacity: 0 }, to: { opacity: 1 } },
            '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
          }}
        />
      ))}
    </Box>
  );
}
