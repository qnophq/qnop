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
const drift = keyframes`
  0%, 100% { transform: translateY(0) rotate(0deg); }
  50% { transform: translateY(-11px) rotate(-2.5deg); }
`;
const badgePulse = keyframes`
  0%, 100% { transform: scale(1); opacity: 0.95; }
  50% { transform: scale(1.08); opacity: 1; }
`;
const halo = keyframes`
  0%, 100% { transform: scale(0.9); opacity: 0.5; }
  50% { transform: scale(1.3); opacity: 0; }
`;
const twinkle = keyframes`
  0%, 100% { opacity: 0.25; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1); }
`;
const popIn = keyframes`
  from { opacity: 0; transform: scale(0.5) translateY(6px); }
  to { opacity: 1; transform: none; }
`;

/** One hovering signal — the shape a notification takes before it lands. */
function Signal({
  x,
  y,
  color,
  delay,
  children,
}: {
  x: number;
  y: number;
  color: string;
  delay: number;
  children: React.ReactNode;
}) {
  return (
    <g
      className="signal"
      style={{ transformOrigin: `${x}px ${y}px`, animationDelay: `${delay}ms` }}
    >
      <g className="bob" style={{ transformOrigin: `${x}px ${y}px`, animationDelay: `${delay}ms` }}>
        <circle cx={x} cy={y} r={19} fill={color} />
        {/* the little tail that makes a circle read as a message bubble */}
        <path d={`M ${x - 5} ${y + 16} l -4 9 l 11 -6 z`} fill={color} />
        <g transform={`translate(${x - 9} ${y - 9})`} stroke="#fff" strokeWidth={2.1} fill="none">
          {children}
        </g>
      </g>
    </g>
  );
}

/**
 * "The inbox is wired up and listening" — an open tray at rest while three
 * signals (a mention, a reply, a decision) hover above it, not yet landed, with
 * a pulsing beacon on the tray's lip.
 *
 * It deliberately reads as *ready* rather than as *broken*: an empty inbox is
 * not a failure state, it is a quiet one. Same visual language as the reviews
 * and my-teams empty states — brand-palette shapes, a floating scene, an
 * inviting accent beacon. All motion is disabled under reduced-motion.
 */
export function InboxIllustration() {
  const theme = useTheme();
  const palette = theme.qnop.avatarPalette;
  const accent = theme.palette.primary.main;
  const paper = theme.palette.background.paper;
  const line = theme.palette.divider;
  // The lip needs to carry the whole "this is a tray, and it is empty" read, so
  // it is drawn in ink rather than in the barely-there divider tone.
  const ink = theme.palette.text.disabled;

  return (
    <Box
      component="svg"
      viewBox="0 0 320 210"
      role="img"
      aria-label="An empty inbox tray waiting for its first notification"
      sx={{
        width: '100%',
        maxWidth: 340,
        height: 'auto',
        display: 'block',
        '& .float': { animation: `${floaty} 5.5s ease-in-out infinite` },
        '& .signal': { animation: `${popIn} 560ms cubic-bezier(0.16, 1, 0.3, 1) both` },
        '& .bob': { animation: `${drift} 4.8s ease-in-out infinite` },
        '& .beacon': {
          transformOrigin: '219px 105px',
          animation: `${badgePulse} 2.4s ease-in-out infinite`,
        },
        '& .beacon-halo': {
          transformOrigin: '219px 105px',
          animation: `${halo} 2.4s ease-in-out infinite`,
        },
        '& .spark': { animation: `${twinkle} 3s ease-in-out infinite` },
        '@media (prefers-reduced-motion: reduce)': {
          '& .float, & .signal, & .bob, & .beacon, & .beacon-halo, & .spark': {
            animation: 'none',
          },
        },
      }}
    >
      <defs>
        <filter id="qnop-inbox-shadow" x="-30%" y="-30%" width="160%" height="160%">
          <feDropShadow dx="0" dy="7" stdDeviation="9" floodColor="#012142" floodOpacity="0.16" />
        </filter>
      </defs>

      {/* soft ground shadow */}
      <ellipse cx="160" cy="192" rx="78" ry="12" fill={accent} opacity={0.08} />

      {/* the three signals, still in the air */}
      <Signal x={96} y={56} color={accent} delay={140}>
        {/* @ — a mention */}
        <circle cx="9" cy="9" r="4.4" />
        <path d="M13.4 9 v2.2 a3.4 3.4 0 0 0 3.4 -3.4 a7.8 7.8 0 1 0 -3 6.2" />
      </Signal>
      <Signal x={160} y={38} color={palette[1] ?? accent} delay={280}>
        {/* speech bubble — a reply */}
        <path d="M1.4 4.6 a2.4 2.4 0 0 1 2.4 -2.4 h10.4 a2.4 2.4 0 0 1 2.4 2.4 v6.4 a2.4 2.4 0 0 1 -2.4 2.4 h-6.6 l-4.4 3.4 v-3.4 h-1.4 a2.4 2.4 0 0 1 -2.4 -2.4 z" />
      </Signal>
      <Signal x={224} y={56} color={palette[2] ?? accent} delay={420}>
        {/* check — a decision */}
        <path d="M2.6 9.6 l4.6 4.6 l8.4 -9.6" strokeLinecap="round" strokeLinejoin="round" />
      </Signal>

      {/* twinkles between the signals */}
      <circle className="spark" cx="126" cy="26" r="2.6" fill={accent} />
      <circle
        className="spark"
        cx="196"
        cy="20"
        r="2.1"
        fill={accent}
        style={{ animationDelay: '900ms' }}
      />
      <circle
        className="spark"
        cx="262"
        cy="34"
        r="2.4"
        fill={palette[2] ?? accent}
        style={{ animationDelay: '1600ms' }}
      />

      <g className="float">
        {/* the tray, in the shape the inbox glyph everywhere else already uses */}
        <g filter="url(#qnop-inbox-shadow)">
          <rect
            x="98"
            y="102"
            width="124"
            height="76"
            rx="15"
            fill={paper}
            stroke={line}
            strokeWidth="2"
          />
          {/* below the lip is the tray floor — tinted for depth, and empty */}
          <path
            d="M98 132 h30 l9 13 h46 l9 -13 h30 v31 a15 15 0 0 1 -15 15 h-94 a15 15 0 0 1 -15 -15 z"
            fill={accent}
            opacity={0.1}
          />
          {/* the lip itself: the line that makes a rounded square read as a tray */}
          <path
            d="M98 132 h30 l9 13 h46 l9 -13 h30"
            fill="none"
            stroke={ink}
            strokeWidth="2.4"
            strokeLinejoin="round"
            strokeLinecap="round"
            opacity={0.55}
          />
          {/* two short inner walls: without them the mouth reads as an envelope
              flap rather than as an opening you could drop something into */}
          <path
            d="M112 146 v18 M208 146 v18"
            stroke={ink}
            strokeWidth="2"
            strokeLinecap="round"
            opacity={0.22}
          />
        </g>

        {/* the beacon rides the rim like a badge: armed, listening, nothing yet */}
        <circle className="beacon-halo" cx="219" cy="105" r="13" fill={accent} opacity={0.45} />
        <circle className="beacon" cx="219" cy="105" r="8" fill={accent} />
        <circle cx="219" cy="105" r="3" fill="#fff" opacity={0.92} />
      </g>
    </Box>
  );
}
