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

import type { ReactNode } from 'react';
import { alpha, useTheme } from '@mui/material/styles';

/**
 * The error pages' illustration set (issue #611): self-drawn line art in the
 * document/review metaphor, so licensing is clean by construction (ADR-0007).
 * Every motif is stroke-based on theme tokens — one drawing holds up on both
 * backgrounds, at any DPI. All illustrations are decorative (aria-hidden by
 * the shell); the copy carries the meaning.
 */
interface IlluColors {
  /** Primary line work. */
  line: string;
  /** Secondary/structural lines. */
  faint: string;
  /** The one brand accent per motif. */
  accent: string;
  /** Soft accent fill. */
  wash: string;
}

function useIlluColors(): IlluColors {
  const theme = useTheme();
  const accent = theme.qnop.brand.blue;
  return {
    line: theme.palette.text.secondary,
    faint: theme.palette.divider,
    accent,
    wash: alpha(accent, theme.qnop.mode === 'dark' ? 0.18 : 0.1),
  };
}

/** Shared canvas: fixed viewBox, responsive width, round joins throughout. */
function Canvas({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 220 150"
      width="220"
      height="150"
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ maxWidth: '100%', height: 'auto' }}
    >
      {children}
    </svg>
  );
}

/** 404 — a paper plane folded from a review page sails off the edge of the desk. */
export function NotFoundIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Desk edge, ending mid-air. */}
      <path d="M14 112 H132" stroke={c.line} strokeWidth="2.5" />
      <path d="M132 112 L138 118" stroke={c.faint} strokeWidth="2" />
      {/* The page it was folded from, lines wandering off. */}
      <rect x="30" y="70" width="52" height="36" rx="4" stroke={c.faint} strokeWidth="2" />
      <path d="M38 80 H66 M38 88 H58 M38 96 H62" stroke={c.faint} strokeWidth="2" />
      {/* Dashed flight path over the edge. */}
      <path
        d="M84 92 C 116 78, 138 74, 168 52"
        stroke={c.accent}
        strokeWidth="2"
        strokeDasharray="2 7"
      />
      {/* The plane. */}
      <path d="M168 52 L196 40 L182 62 Z" fill={c.wash} stroke={c.accent} strokeWidth="2.5" />
      <path d="M182 62 L179 50" stroke={c.accent} strokeWidth="2.5" />
    </Canvas>
  );
}

/** 403 — a ring binder with a very serious little padlock; a boundary, not an accusation. */
export function ForbiddenIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Binder. */}
      <rect x="62" y="34" width="96" height="82" rx="8" stroke={c.line} strokeWidth="2.5" />
      <path d="M80 34 V116" stroke={c.faint} strokeWidth="2" />
      <circle cx="80" cy="58" r="5" stroke={c.line} strokeWidth="2" />
      <circle cx="80" cy="92" r="5" stroke={c.line} strokeWidth="2" />
      {/* Spine label. */}
      <path d="M96 50 H142 M96 60 H128" stroke={c.faint} strokeWidth="2" />
      {/* The padlock, front and centre. */}
      <rect
        x="106"
        y="80"
        width="30"
        height="24"
        rx="5"
        fill={c.wash}
        stroke={c.accent}
        strokeWidth="2.5"
      />
      <path d="M112 80 V72 a9 9 0 0 1 18 0 V80" stroke={c.accent} strokeWidth="2.5" />
      <circle cx="121" cy="91" r="2.5" fill={c.accent} />
    </Canvas>
  );
}

/** 409 — two annotation pins land on the same line; the speech bubbles collide. */
export function ConflictIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Page with the contested line. */}
      <rect x="66" y="52" width="88" height="66" rx="6" stroke={c.line} strokeWidth="2.5" />
      <path d="M76 66 H144 M76 92 H144 M76 104 H120" stroke={c.faint} strokeWidth="2" />
      <path d="M76 79 H144" stroke={c.accent} strokeWidth="3" />
      {/* Two pins on the same line. */}
      <path d="M92 79 L84 60" stroke={c.line} strokeWidth="2" />
      <circle cx="83" cy="57" r="5" fill={c.wash} stroke={c.accent} strokeWidth="2" />
      <path d="M128 79 L136 60" stroke={c.line} strokeWidth="2" />
      <circle cx="137" cy="57" r="5" fill={c.wash} stroke={c.accent} strokeWidth="2" />
      {/* Colliding bubbles above. */}
      <path d="M60 30 h34 v18 h-24 l-6 7 v-7 h-4 Z" stroke={c.line} strokeWidth="2" />
      <path d="M126 26 h34 v18 h-4 v7 l-6 -7 h-24 Z" stroke={c.line} strokeWidth="2" />
      <path d="M104 34 l12 4 M116 34 l-12 4" stroke={c.accent} strokeWidth="2.5" />
    </Canvas>
  );
}

/** 500 — the shredder ate the document and does not look sorry. Our fault. */
export function ServerErrorIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Shredder body. */}
      <rect x="64" y="62" width="92" height="46" rx="8" stroke={c.line} strokeWidth="2.5" />
      <path d="M74 74 H146" stroke={c.accent} strokeWidth="3" />
      {/* Page half-in. */}
      <path d="M88 62 L92 34 H132 L136 62" stroke={c.line} strokeWidth="2.5" />
      <path d="M98 44 H126 M98 52 H118" stroke={c.faint} strokeWidth="2" />
      {/* Strips below. */}
      <path
        d="M84 108 v16 M96 108 v22 M108 108 v14 M120 108 v20 M132 108 v15"
        stroke={c.faint}
        strokeWidth="2"
      />
      {/* A quiet spark: the one thing it should not have done. */}
      <path d="M158 50 l6 -8 M162 56 l9 -3 M154 44 l2 -10" stroke={c.accent} strokeWidth="2" />
    </Canvas>
  );
}

/** 503 — an "out to lunch" sign hangs on the filing cabinet; back shortly. */
export function MaintenanceIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Filing cabinet. */}
      <rect x="72" y="28" width="76" height="94" rx="6" stroke={c.line} strokeWidth="2.5" />
      <path d="M72 60 H148 M72 92 H148" stroke={c.faint} strokeWidth="2" />
      <path d="M102 44 H118 M102 76 H118" stroke={c.line} strokeWidth="2.5" />
      {/* The sign, hanging slightly askew on a string. */}
      <path d="M110 92 l-7 10 M110 92 l13 8" stroke={c.faint} strokeWidth="2" />
      <g transform="rotate(-6 110 116)">
        <rect
          x="86"
          y="102"
          width="48"
          height="26"
          rx="4"
          fill={c.wash}
          stroke={c.accent}
          strokeWidth="2.5"
        />
        <path d="M94 111 H126 M94 119 H114" stroke={c.accent} strokeWidth="2" />
      </g>
    </Canvas>
  );
}

/** 429 — a reviewer stamping far too fast; stamps flying, a queue forming. */
export function RateLimitIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Paper stack. */}
      <path d="M60 116 H150 M66 110 H144 M72 104 H138" stroke={c.faint} strokeWidth="2" />
      {/* The stamp mid-swing with speed lines. */}
      <g transform="rotate(14 128 66)">
        <rect x="116" y="52" width="24" height="14" rx="3" stroke={c.line} strokeWidth="2.5" />
        <path d="M124 52 V40 h8 v12" stroke={c.line} strokeWidth="2.5" />
        <path d="M112 70 h32" stroke={c.line} strokeWidth="2.5" />
      </g>
      <path d="M96 44 l-12 -6 M98 56 l-14 -1 M100 34 l-9 -10" stroke={c.faint} strokeWidth="2" />
      {/* Stamped marks scattering — each a tidy little approval. */}
      <circle cx="70" cy="80" r="9" stroke={c.accent} strokeWidth="2" fill={c.wash} />
      <path d="M66 80 l3 3 l5 -6" stroke={c.accent} strokeWidth="2" />
      <circle cx="100" cy="88" r="9" stroke={c.accent} strokeWidth="2" />
      <path d="M96 88 l3 3 l5 -6" stroke={c.accent} strokeWidth="2" />
      <circle cx="132" cy="92" r="9" stroke={c.accent} strokeWidth="2" fill={c.wash} />
      <path d="M128 92 l3 3 l5 -6" stroke={c.accent} strokeWidth="2" />
    </Canvas>
  );
}

/** Offline — two paper cups, and the string between them is cut. Calm, not alarming. */
export function OfflineIllustration() {
  const c = useIlluColors();
  return (
    <Canvas>
      {/* Left cup. */}
      <path d="M40 78 h30 l-4 34 h-22 Z" stroke={c.line} strokeWidth="2.5" />
      <path d="M44 88 h22" stroke={c.faint} strokeWidth="2" />
      {/* Right cup. */}
      <path d="M150 78 h30 l-4 34 h-22 Z" stroke={c.line} strokeWidth="2.5" />
      <path d="M154 88 h22" stroke={c.faint} strokeWidth="2" />
      {/* The string, sagging — and cut in the middle. */}
      <path d="M70 82 C 88 96, 98 100, 104 101" stroke={c.accent} strokeWidth="2" />
      <path d="M150 82 C 132 96, 122 100, 116 101" stroke={c.accent} strokeWidth="2" />
      <path d="M104 101 l-3 6 M116 101 l3 6" stroke={c.accent} strokeWidth="2" />
      {/* A last little signal that did not make it across. */}
      <path d="M107 92 a6 6 0 0 1 6 0" stroke={c.faint} strokeWidth="2" strokeDasharray="2 4" />
    </Canvas>
  );
}
