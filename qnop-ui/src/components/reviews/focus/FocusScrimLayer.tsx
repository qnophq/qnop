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
import { tokens } from '../../../theme/tokens';
import { holePolygon } from './spotlightModel';

interface FocusScrimLayerProps {
  /** The sharp region when the spotlight targets THIS page; null dims it fully. */
  spotlight: NormalizedBox | null;
  /** Clicking the dimmed area closes the overlay. */
  onDismiss: () => void;
  surfaceIndex: number;
}

/**
 * The focus mode's per-page scrim (issue #291): the page dims under a soft
 * veil while the spotlit passage stays untouched — the hole is cut with a
 * clip-path, never a filter over the mark, so the anchored text keeps its
 * full contrast (the marks themselves paint on the layer above). The clipped
 * region is also transparent to pointer events, so the spotlit passage stays
 * selectable while the veil catches dismiss clicks. Moving the spotlight
 * (prev/next) morphs the clip smoothly — clip-path animates on the
 * compositor; `prefers-reduced-motion` snaps instead, and
 * `prefers-reduced-transparency` drops the blur.
 */
export function FocusScrimLayer({ spotlight, onDismiss, surfaceIndex }: FocusScrimLayerProps) {
  return (
    <Box
      data-testid={`focus-scrim-${surfaceIndex}`}
      onClick={onDismiss}
      sx={{
        position: 'absolute',
        inset: 0,
        pointerEvents: 'auto',
        cursor: 'pointer',
        bgcolor: 'rgba(1, 32, 66, 0.32)',
        backdropFilter: 'blur(1.5px)',
        '@media (prefers-reduced-transparency: reduce)': { backdropFilter: 'none' },
        clipPath: spotlight ? holePolygon(spotlight) : undefined,
        transition: `clip-path ${tokens.motion.durSlow}ms ${tokens.motion.easeInOut}`,
        animation: 'qnopScrimIn 200ms ease-out',
        '@keyframes qnopScrimIn': { from: { opacity: 0 }, to: { opacity: 1 } },
        '@media (prefers-reduced-motion: reduce)': { animation: 'none', transition: 'none' },
      }}
    />
  );
}
