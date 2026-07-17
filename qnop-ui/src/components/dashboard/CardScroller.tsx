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

import { useRef, useState, type ReactNode, type UIEvent } from 'react';
import Box from '@mui/material/Box';
import { alpha, useTheme } from '@mui/material/styles';

/** One viewport for every dashboard card — the cockpit stays one screen. */
const DEFAULT_MAX_HEIGHT = 336;
const EDGE_TOLERANCE = 4;

interface CardScrollerProps {
  maxHeight?: number;
  children: ReactNode;
}

/**
 * The dashboard cards' content viewport (issue #454 follow-up): lists grew
 * with real data, so every card caps at one height and scrolls internally.
 * Soft fade masks at the edges say "there is more" without a visible border —
 * the bottom fade lifts once the list is fully read, a top fade appears once
 * scrolled. The scrollbar stays a quiet hairline.
 */
export function CardScroller({ maxHeight = DEFAULT_MAX_HEIGHT, children }: CardScrollerProps) {
  const theme = useTheme();
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const [edges, setEdges] = useState({ above: false, below: false });

  const measure = (el: HTMLDivElement | null) => {
    if (!el) return;
    const below = el.scrollTop + el.clientHeight < el.scrollHeight - EDGE_TOLERANCE;
    const above = el.scrollTop > EDGE_TOLERANCE;
    setEdges((current) =>
      current.above === above && current.below === below ? current : { above, below },
    );
  };

  const handleScroll = (event: UIEvent<HTMLDivElement>) => measure(event.currentTarget);

  const fade = (direction: 'top' | 'bottom', visible: boolean) => ({
    content: '""',
    position: 'absolute' as const,
    left: 0,
    right: 0,
    [direction]: 0,
    height: 36,
    pointerEvents: 'none' as const,
    opacity: visible ? 1 : 0,
    transition: 'opacity 160ms ease',
    background: `linear-gradient(${direction === 'top' ? 'to bottom' : 'to top'}, ${
      theme.palette.background.paper
    }, ${alpha(theme.palette.background.paper, 0)})`,
    zIndex: 1,
  });

  return (
    <Box sx={{ position: 'relative' }}>
      <Box sx={fade('top', edges.above)} aria-hidden />
      <Box
        ref={(el: HTMLDivElement | null) => {
          viewportRef.current = el;
          measure(el);
        }}
        onScroll={handleScroll}
        sx={{
          maxHeight,
          overflowY: 'auto',
          // A quiet hairline scrollbar, matching the product's calm chrome.
          scrollbarWidth: 'thin',
          scrollbarColor: `${alpha(theme.palette.text.primary, 0.18)} transparent`,
          '&::-webkit-scrollbar': { width: 6 },
          '&::-webkit-scrollbar-thumb': {
            borderRadius: 3,
            backgroundColor: alpha(theme.palette.text.primary, 0.18),
          },
          '&::-webkit-scrollbar-track': { backgroundColor: 'transparent' },
        }}
      >
        {children}
      </Box>
      <Box sx={fade('bottom', edges.below)} aria-hidden />
    </Box>
  );
}
