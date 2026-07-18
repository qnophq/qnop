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
const seatPulse = keyframes`
  0%, 100% { transform: scale(1); opacity: 0.9; }
  50% { transform: scale(1.06); opacity: 1; }
`;
const ringPulse = keyframes`
  0%, 100% { transform: scale(0.9); opacity: 0.5; }
  50% { transform: scale(1.15); opacity: 0; }
`;
const twinkle = keyframes`
  0%, 100% { opacity: 0.25; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1); }
`;
const popIn = keyframes`
  from { opacity: 0; transform: scale(0.6) translateY(6px); }
  to { opacity: 1; transform: none; }
`;

/** One glossy teammate bubble (matches the app's avatar look). */
function Teammate({
  cx,
  cy,
  r,
  color,
  delay,
}: {
  cx: number;
  cy: number;
  r: number;
  color: string;
  delay: number;
}) {
  return (
    <g className="av" style={{ transformOrigin: `${cx}px ${cy}px`, animationDelay: `${delay}ms` }}>
      <circle cx={cx} cy={cy} r={r} fill={color} />
      <circle cx={cx - r * 0.3} cy={cy - r * 0.34} r={r * 0.42} fill="#fff" opacity={0.22} />
    </g>
  );
}

/**
 * "Your seat is waiting" — a teammate huddle with one pulsing open seat and a few
 * twinkling sparkles (issue #470). Colours come from the brand avatar palette so
 * the bubbles read like real teammates on both themes; the dashed seat and its
 * halo use the brand accent. The huddle floats, the seat breathes, and the whole
 * scene pops in on load — all disabled under reduced-motion.
 */
export function JoinTeamIllustration() {
  const theme = useTheme();
  const p = theme.qnop.avatarPalette;
  const accent = theme.palette.primary.main;
  const seat = theme.palette.text.disabled;

  return (
    <Box
      component="svg"
      viewBox="0 0 320 210"
      role="img"
      aria-label="An open seat waiting in a team"
      sx={{
        width: '100%',
        maxWidth: 340,
        height: 'auto',
        display: 'block',
        '& .float': { animation: `${floaty} 5.5s ease-in-out infinite` },
        '& .av': { animation: `${popIn} 520ms cubic-bezier(0.16, 1, 0.3, 1) both` },
        '& .seat': {
          transformOrigin: '150px 150px',
          animation: `${seatPulse} 2.4s ease-in-out infinite`,
        },
        '& .seat-halo': {
          transformOrigin: '150px 150px',
          animation: `${ringPulse} 2.4s ease-in-out infinite`,
        },
        '& .spark': { animation: `${twinkle} 3s ease-in-out infinite` },
        '@media (prefers-reduced-motion: reduce)': {
          '& .float, & .av, & .seat, & .seat-halo, & .spark': { animation: 'none' },
        },
      }}
    >
      {/* soft ground shadow */}
      <ellipse cx="160" cy="188" rx="118" ry="16" fill={accent} opacity={0.08} />

      <g className="float">
        {/* faint team orbit behind the huddle */}
        <ellipse
          cx="160"
          cy="112"
          rx="118"
          ry="60"
          fill="none"
          stroke={accent}
          strokeWidth="1.5"
          strokeDasharray="2 8"
          strokeLinecap="round"
          opacity={0.35}
        />

        {/* the huddle — real teammates */}
        <Teammate cx={82} cy={116} r={24} color={p[2]} delay={0} />
        <Teammate cx={250} cy={120} r={22} color={p[3]} delay={80} />
        <Teammate cx={135} cy={92} r={27} color={p[0]} delay={160} />
        <Teammate cx={205} cy={98} r={26} color={p[5]} delay={240} />

        {/* the open seat — your spot */}
        <g style={{ transformOrigin: '150px 150px' }}>
          <circle className="seat-halo" cx="150" cy="150" r="34" fill={accent} opacity={0.14} />
          <g className="seat">
            <circle
              cx="150"
              cy="150"
              r="30"
              fill={accent}
              fillOpacity={0.06}
              stroke={seat}
              strokeWidth="2.5"
              strokeDasharray="5 6"
              strokeLinecap="round"
            />
            <path
              d="M150 138 v24 M138 150 h24"
              stroke={accent}
              strokeWidth="4"
              strokeLinecap="round"
            />
          </g>
        </g>
      </g>

      {/* sparkles */}
      <circle
        className="spark"
        cx="52"
        cy="64"
        r="3.5"
        fill={accent}
        style={{ animationDelay: '0ms' }}
      />
      <circle
        className="spark"
        cx="286"
        cy="72"
        r="2.5"
        fill={p[3]}
        style={{ animationDelay: '600ms' }}
      />
      <circle
        className="spark"
        cx="292"
        cy="158"
        r="2.5"
        fill={p[5]}
        style={{ animationDelay: '1200ms' }}
      />
      <circle
        className="spark"
        cx="40"
        cy="150"
        r="2.5"
        fill={p[2]}
        style={{ animationDelay: '1800ms' }}
      />
    </Box>
  );
}
