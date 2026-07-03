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
import type { PointerEvent } from 'react';
import Box from '@mui/material/Box';
import { alpha, useTheme } from '@mui/material/styles';
import type { NormalizedBox } from '../../../api/generated';
import type { ScreenPosition } from './anchoring';

interface RegionSelectLayerProps {
  surfaceIndex: number;
  enabled: boolean;
  onRegionSelected: (surfaceIndex: number, box: NormalizedBox, at: ScreenPosition) => void;
}

interface DraftRect {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

function clampUnit(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/**
 * Rubber-band region selection: drag a rectangle anywhere on the surface —
 * the universal anchor layer that works with or without a text layer
 * (ADR-0009). Coordinates are normalized against the page box, so the drawn
 * region is zoom- and DPI-independent (ADR-0032).
 */
export function RegionSelectLayer({
  surfaceIndex,
  enabled,
  onRegionSelected,
}: RegionSelectLayerProps) {
  const theme = useTheme();
  const rootRef = useRef<HTMLDivElement>(null);
  const [draft, setDraft] = useState<DraftRect | null>(null);

  const toNormalized = (event: PointerEvent) => {
    const rect = rootRef.current!.getBoundingClientRect();
    return {
      x: clampUnit((event.clientX - rect.left) / rect.width),
      y: clampUnit((event.clientY - rect.top) / rect.height),
    };
  };

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (!enabled || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const point = toNormalized(event);
    setDraft({ x1: point.x, y1: point.y, x2: point.x, y2: point.y });
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!draft) return;
    const point = toNormalized(event);
    setDraft({ ...draft, x2: point.x, y2: point.y });
  };

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (!draft) return;
    setDraft(null);
    onRegionSelected(
      surfaceIndex,
      { x: draft.x1, y: draft.y1, width: draft.x2 - draft.x1, height: draft.y2 - draft.y1 },
      { left: event.clientX, top: event.clientY },
    );
  };

  const preview: NormalizedBox | null = draft && {
    x: Math.min(draft.x1, draft.x2),
    y: Math.min(draft.y1, draft.y2),
    width: Math.abs(draft.x2 - draft.x1),
    height: Math.abs(draft.y2 - draft.y1),
  };

  return (
    <Box
      ref={rootRef}
      data-testid={`region-layer-${surfaceIndex}`}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      sx={{
        position: 'absolute',
        inset: 0,
        cursor: 'crosshair',
        pointerEvents: enabled ? 'auto' : 'none',
        touchAction: 'none',
      }}
    >
      {preview && (
        <Box
          sx={{
            position: 'absolute',
            left: `${preview.x * 100}%`,
            top: `${preview.y * 100}%`,
            width: `${preview.width * 100}%`,
            height: `${preview.height * 100}%`,
            border: `2px dashed ${theme.qnop.brand.blue}`,
            bgcolor: alpha(theme.qnop.brand.blue, 0.08),
            pointerEvents: 'none',
          }}
        />
      )}
    </Box>
  );
}
