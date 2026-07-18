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
import { keyframes, useTheme } from '@mui/material/styles';

const floaty = keyframes`
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-7px); }
`;
const badgePulse = keyframes`
  0%, 100% { transform: scale(1); opacity: 0.95; }
  50% { transform: scale(1.08); opacity: 1; }
`;
const halo = keyframes`
  0%, 100% { transform: scale(0.9); opacity: 0.5; }
  50% { transform: scale(1.25); opacity: 0; }
`;
const twinkle = keyframes`
  0%, 100% { opacity: 0.25; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1); }
`;
const popIn = keyframes`
  from { opacity: 0; transform: scale(0.5) translateY(4px); }
  to { opacity: 1; transform: none; }
`;
const highlight = keyframes`
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.9; }
`;

/** A margin comment dot — a review annotation clinging to the page edge. */
function Annotation({
  cx,
  cy,
  color,
  delay,
}: {
  cx: number;
  cy: number;
  color: string;
  delay: number;
}) {
  return (
    <g className="pin" style={{ transformOrigin: `${cx}px ${cy}px`, animationDelay: `${delay}ms` }}>
      <path d={`M ${cx - 8} ${cy} l -7 4 l 0 -8 z`} fill={color} />
      <circle cx={cx} cy={cy} r={10} fill={color} />
      <circle cx={cx} cy={cy} r={3.4} fill="#fff" opacity={0.9} />
    </g>
  );
}

/**
 * "A fresh page waiting for its first mark" — a floating review document with
 * text lines, a pulsing highlighted line, margin annotation dots that pop in and
 * a pulsing "+" start-badge (issue #470). Same visual language as the My Teams
 * empty state: brand-palette shapes, a floating scene, and an inviting accent
 * beacon. All motion is disabled under reduced-motion.
 */
export function ReviewIllustration() {
  const theme = useTheme();
  const p = theme.qnop.avatarPalette;
  const accent = theme.palette.primary.main;
  const ink = theme.palette.text.disabled;
  const paper = theme.palette.background.paper;
  const line = theme.palette.divider;

  const lines = [
    { y: 62, w: 66 },
    { y: 78, w: 78 },
    { y: 94, w: 50 },
    { y: 132, w: 72 },
    { y: 148, w: 40 },
  ];

  return (
    <Box
      component="svg"
      viewBox="0 0 320 210"
      role="img"
      aria-label="A fresh document waiting for its first review"
      sx={{
        width: '100%',
        maxWidth: 340,
        height: 'auto',
        display: 'block',
        '& .float': { animation: `${floaty} 5.5s ease-in-out infinite` },
        '& .pop': { animation: `${popIn} 560ms cubic-bezier(0.16, 1, 0.3, 1) both` },
        '& .pin': { animation: `${popIn} 520ms cubic-bezier(0.16, 1, 0.3, 1) both` },
        '& .hl': { animation: `${highlight} 2.6s ease-in-out infinite` },
        '& .badge': {
          transformOrigin: '198px 176px',
          animation: `${badgePulse} 2.4s ease-in-out infinite`,
        },
        '& .badge-halo': {
          transformOrigin: '198px 176px',
          animation: `${halo} 2.4s ease-in-out infinite`,
        },
        '& .spark': { animation: `${twinkle} 3s ease-in-out infinite` },
        '@media (prefers-reduced-motion: reduce)': {
          '& .float, & .pop, & .pin, & .hl, & .badge, & .badge-halo, & .spark': {
            animation: 'none',
          },
        },
      }}
    >
      <defs>
        <filter id="qnop-doc-shadow" x="-30%" y="-30%" width="160%" height="160%">
          <feDropShadow dx="0" dy="7" stdDeviation="9" floodColor="#012142" floodOpacity="0.16" />
        </filter>
      </defs>

      {/* soft ground shadow */}
      <ellipse cx="160" cy="190" rx="104" ry="15" fill={accent} opacity={0.08} />

      <g className="float">
        {/* the document sheet */}
        <g className="pop" style={{ transformOrigin: '159px 114px' }}>
          <rect
            x="104"
            y="38"
            width="110"
            height="152"
            rx="12"
            fill={paper}
            stroke={line}
            strokeWidth="1.5"
            filter="url(#qnop-doc-shadow)"
          />
          {/* the line being reviewed, gently highlighted */}
          <rect
            className="hl"
            x="115"
            y="109"
            width="88"
            height="15"
            rx="4"
            fill={accent}
            opacity={0.14}
          />
          {lines.map((l) => (
            <rect
              key={l.y}
              x="120"
              y={l.y}
              width={l.w}
              height="6"
              rx="3"
              fill={ink}
              opacity={0.32}
            />
          ))}
          <rect x="120" y="116" width="70" height="6" rx="3" fill={accent} opacity={0.55} />
        </g>

        {/* margin annotations */}
        <Annotation cx={210} cy={82} color={p[2]} delay={280} />
        <Annotation cx={216} cy={140} color={p[5]} delay={420} />

        {/* the "+" start beacon */}
        <g style={{ transformOrigin: '198px 176px' }}>
          <circle className="badge-halo" cx="198" cy="176" r="22" fill={accent} opacity={0.16} />
          <g className="badge">
            <circle cx="198" cy="176" r="18" fill={accent} />
            <path
              d="M198 168 v16 M190 176 h16"
              stroke="#fff"
              strokeWidth="3.2"
              strokeLinecap="round"
            />
          </g>
        </g>
      </g>

      {/* sparkles */}
      <circle
        className="spark"
        cx="58"
        cy="60"
        r="3.5"
        fill={accent}
        style={{ animationDelay: '0ms' }}
      />
      <circle
        className="spark"
        cx="270"
        cy="58"
        r="2.5"
        fill={p[3]}
        style={{ animationDelay: '600ms' }}
      />
      <circle
        className="spark"
        cx="278"
        cy="150"
        r="2.5"
        fill={p[2]}
        style={{ animationDelay: '1200ms' }}
      />
      <circle
        className="spark"
        cx="46"
        cy="152"
        r="2.5"
        fill={p[5]}
        style={{ animationDelay: '1800ms' }}
      />
    </Box>
  );
}
