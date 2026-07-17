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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, keyframes } from '@mui/material/styles';
import { CheckCheck, Crown, Flame, MessageSquare } from 'lucide-react';
import { tokens } from '../../theme/tokens';

const float = keyframes`
  from { transform: translateY(0); }
  to   { transform: translateY(-9px); }
`;

/** A floating layer of the collage: rotation on the wrapper, bob on the child. */
function Floating({
  tilt,
  delay,
  sx,
  children,
}: {
  tilt: number;
  delay: number;
  sx?: object;
  children: React.ReactNode;
}) {
  return (
    <Box sx={{ transform: `rotate(${tilt}deg)`, ...sx }}>
      <Box
        sx={{
          animation: `${float} 5.5s ease-in-out ${delay}s infinite alternate`,
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}
      >
        {children}
      </Box>
    </Box>
  );
}

const STATS = [
  { value: '128', label: 'Reviews' },
  { value: '1.4k', label: 'Annotations' },
  { value: '14d', label: 'Streak' },
] as const;

/** Text-line widths of the miniature document page, in percent. */
const DOC_LINES = [92, 78, 88, 62] as const;

/**
 * A miniature annotated document page — the thing qnop is actually about —
 * peeking out from behind the player card: a few abstract text lines with one
 * line-precise highlight and its comment marker.
 */
function DocumentMiniature() {
  const blue = tokens.brand.blue;
  return (
    <Box
      sx={{
        width: 168,
        borderRadius: '10px',
        p: 1.75,
        bgcolor: tokens.light.surface,
        border: `1px solid ${tokens.light.border}`,
        boxShadow: '0 18px 48px -12px rgba(0,10,25,0.5)',
      }}
    >
      <Box sx={{ height: 7, width: '46%', borderRadius: 2, bgcolor: tokens.light.fg2, mb: 1.25 }} />
      {DOC_LINES.slice(0, 2).map((width) => (
        <Box
          key={width}
          sx={{
            height: 5,
            width: `${width}%`,
            borderRadius: 2,
            bgcolor: tokens.light.borderStrong,
            mb: 0.875,
          }}
        />
      ))}
      {/* the highlighted line with its comment marker */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.875 }}>
        <Box
          sx={{
            flex: 1,
            height: 11,
            borderRadius: '3px',
            bgcolor: alpha(blue, 0.16),
            border: `1px solid ${alpha(blue, 0.35)}`,
            display: 'flex',
            alignItems: 'center',
            px: 0.625,
          }}
        >
          <Box sx={{ height: 5, width: '82%', borderRadius: 2, bgcolor: alpha(blue, 0.55) }} />
        </Box>
        <MessageSquare size={12} style={{ color: blue, flexShrink: 0 }} />
      </Box>
      {DOC_LINES.slice(2).map((width) => (
        <Box
          key={width}
          sx={{
            height: 5,
            width: `${width}%`,
            borderRadius: 2,
            bgcolor: tokens.light.borderStrong,
            mb: 0.875,
          }}
        />
      ))}
    </Box>
  );
}

/**
 * Decorative product showcase for the auth brand panel (#503): a fictional
 * reviewer's player card in the exact visual vocabulary of the real profile
 * hover card (#473/#482) — identity band, ring avatar, lead-team chip and the
 * three-stat scoreboard — plus a streak pill and a resolved-annotation toast
 * floating around it. Purely presentational: static markup, no data, no hooks;
 * hidden from assistive tech. The panel behind it is always dark, so the card
 * intentionally uses fixed light-surface colors from the token set.
 */
export function ShowcasePlayerCard() {
  const blue = tokens.brand.blue;
  return (
    <Box aria-hidden sx={{ position: 'relative', width: 316, pointerEvents: 'none' }}>
      {/* The annotated document, peeking out from behind the card */}
      <Floating tilt={-8} delay={2.6} sx={{ position: 'absolute', top: -44, left: -64, zIndex: 0 }}>
        <DocumentMiniature />
      </Floating>

      {/* The player card */}
      <Floating tilt={-3} delay={0} sx={{ position: 'relative', zIndex: 1 }}>
        <Box
          sx={{
            borderRadius: '14px',
            overflow: 'hidden',
            bgcolor: tokens.light.surface,
            border: `1px solid ${tokens.light.border}`,
            boxShadow: '0 24px 64px -12px rgba(0,10,25,0.55)',
          }}
        >
          <Box
            sx={{
              height: 52,
              background: `
                radial-gradient(70% 160% at 80% 0%, ${alpha(blue, 0.28)} 0%, transparent 100%),
                linear-gradient(120deg, ${alpha(blue, 0.16)}, ${alpha(blue, 0.04)})
              `,
            }}
          />
          <Box sx={{ px: 2, pb: 2 }}>
            <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-end', mt: -2.75 }}>
              <Box
                sx={{
                  display: 'inline-flex',
                  borderRadius: '50%',
                  p: '2px',
                  bgcolor: tokens.light.surface,
                  border: `2px solid ${alpha(blue, 0.45)}`,
                }}
              >
                <Box
                  sx={{
                    width: 46,
                    height: 46,
                    borderRadius: '50%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    bgcolor: tokens.avatarPalette[5],
                    color: '#fff',
                    fontWeight: 700,
                    fontSize: 17,
                  }}
                >
                  MN
                </Box>
              </Box>
              <Box sx={{ pb: 0.25 }}>
                <Typography sx={{ fontWeight: 800, fontSize: 15.5, color: tokens.light.fg }}>
                  Mira Novak
                </Typography>
                <Typography sx={{ fontSize: 11.5, color: tokens.light.fg3 }}>
                  Member since 2024
                </Typography>
              </Box>
            </Stack>

            <Stack
              direction="row"
              spacing={0.5}
              sx={{
                alignItems: 'center',
                mt: 1.25,
                width: 'fit-content',
                px: 0.875,
                py: 0.375,
                borderRadius: 999,
                border: `1px solid ${tokens.badge.amber.border}`,
                bgcolor: tokens.badge.amber.bg,
              }}
            >
              <Crown size={11} style={{ color: tokens.semantic.warning }} />
              <Typography sx={{ fontSize: 11, fontWeight: 600, color: tokens.badge.amber.fg }}>
                Contracts Team · Lead
              </Typography>
            </Stack>

            <Stack
              direction="row"
              sx={{
                mt: 1.5,
                borderRadius: '10px',
                border: `1px solid ${tokens.light.border}`,
                bgcolor: tokens.light.surface2,
                '& > *': { flex: 1, textAlign: 'center', py: 1 },
                '& > * + *': { borderLeft: `1px solid ${tokens.light.border}` },
              }}
            >
              {STATS.map(({ value, label }) => (
                <Box key={label}>
                  <Typography sx={{ fontWeight: 800, fontSize: 16, color: tokens.light.fg }}>
                    {value}
                  </Typography>
                  <Typography
                    sx={{
                      fontSize: 9.5,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: tokens.light.fg3,
                    }}
                  >
                    {label}
                  </Typography>
                </Box>
              ))}
            </Stack>
          </Box>
        </Box>
      </Floating>

      {/* Streak pill */}
      <Floating
        tilt={2.5}
        delay={0.9}
        sx={{ position: 'absolute', top: -22, right: -34, zIndex: 2 }}
      >
        <Stack
          direction="row"
          spacing={0.75}
          sx={{
            alignItems: 'center',
            px: 1.5,
            py: 0.875,
            borderRadius: 999,
            bgcolor: '#2A2005',
            border: `1px solid ${alpha(tokens.semantic.warning, 0.45)}`,
            boxShadow: '0 12px 32px -8px rgba(0,10,25,0.5)',
          }}
        >
          <Flame size={15} style={{ color: tokens.semantic.warning }} />
          <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: tokens.badge.amber.fgDark }}>
            14-day review streak
          </Typography>
        </Stack>
      </Floating>

      {/* Resolved toast — kept below the card's scoreboard row, not over it. */}
      <Floating
        tilt={-1.5}
        delay={1.8}
        sx={{ position: 'absolute', bottom: -38, left: -30, zIndex: 2 }}
      >
        <Stack
          direction="row"
          spacing={1}
          sx={{
            alignItems: 'center',
            px: 1.5,
            py: 1,
            borderRadius: '10px',
            bgcolor: tokens.light.surface,
            border: `1px solid ${tokens.badge.green.border}`,
            boxShadow: '0 16px 40px -10px rgba(0,10,25,0.5)',
          }}
        >
          <Box
            sx={{
              width: 26,
              height: 26,
              borderRadius: '8px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              bgcolor: tokens.badge.green.bg,
            }}
          >
            <CheckCheck size={15} style={{ color: tokens.semantic.success }} />
          </Box>
          <Box>
            <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: tokens.light.fg }}>
              Annotation resolved
            </Typography>
            <Typography sx={{ fontSize: 10.5, color: tokens.light.fg3 }}>
              Release notes v4 · just now
            </Typography>
          </Box>
        </Stack>
      </Floating>
    </Box>
  );
}
