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
import { GitCompareArrows, Highlighter, ShieldCheck, Trophy } from 'lucide-react';
import { useConfig } from '../../api/hooks/useConfig';
import { BrandLogo } from '../branding/BrandLogo';
import { tokens } from '../../theme/tokens';
import { ShowcasePlayerCard } from './ShowcasePlayerCard';
import authBg from '../../assets/auth/auth-bg.webp';

const FEATURES = [
  { icon: Highlighter, label: 'Line-precise annotations' },
  { icon: GitCompareArrows, label: 'Version-aware reviews' },
  { icon: Trophy, label: 'Streaks & scoreboards' },
];

/** Film-grain texture (inline SVG turbulence) that keeps the navy from reading flat. */
const GRAIN =
  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.55'/%3E%3C/svg%3E\")";

/**
 * The dark brand half of the auth screens (#103, redesigned in #503): the
 * sovereign navy atmosphere carries a decorative player-card collage — the
 * gamified review identity (#469) shown before the first sign-in — above the
 * marketing copy block and the compliance trust chips. Always dark regardless
 * of theme; hidden below `md` (the form column shows a compact brand header
 * instead).
 */
export function BrandPanel() {
  // The panel is always dark, so use the dark-surface (light) logo variant (issue #154).
  const logoUrl = useConfig().data?.branding?.logoDark.url;
  return (
    <Box
      sx={{
        display: { xs: 'none', md: 'flex' },
        flexDirection: 'column',
        position: 'relative',
        overflow: 'hidden',
        color: '#fff',
        p: { md: 4.5, lg: 7 },
        background: `linear-gradient(155deg, ${tokens.brand.navy} 0%, ${tokens.brand.navy700} 60%, #034079 100%)`,
      }}
    >
      {/* cinematic backdrop: floating annotated pages (generated brand imagery, #503) */}
      <Box
        aria-hidden
        component="img"
        src={authBg}
        alt=""
        sx={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          objectPosition: 'right center',
          opacity: 0.75,
        }}
      />
      {/* contrast scrim: keeps the copy block and chips readable over the imagery */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: 0,
          background: `linear-gradient(75deg, ${tokens.brand.navy} 6%, rgba(1,33,66,0.55) 38%, rgba(1,33,66,0.12) 68%, transparent 100%),
            linear-gradient(to top, rgba(1,22,44,0.85) 0%, rgba(1,22,44,0.35) 26%, transparent 52%)`,
        }}
      />
      {/* subtle grid + glow atmosphere */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: 0,
          opacity: 0.5,
          backgroundImage:
            'linear-gradient(rgba(255,255,255,0.04) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.04) 1px, transparent 1px)',
          backgroundSize: '48px 48px',
          maskImage: 'radial-gradient(ellipse 80% 70% at 70% 30%, #000 40%, transparent 100%)',
        }}
      />
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          width: 520,
          height: 520,
          borderRadius: '50%',
          top: -120,
          right: -140,
          filter: 'blur(20px)',
          background: 'radial-gradient(circle, rgba(18,144,239,0.35), transparent 65%)',
        }}
      />
      {/* warm counter-glow: ties the streak accent into the atmosphere */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          width: 440,
          height: 440,
          borderRadius: '50%',
          bottom: -180,
          left: -160,
          filter: 'blur(28px)',
          background: 'radial-gradient(circle, rgba(245,184,61,0.13), transparent 62%)',
        }}
      />
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: 0,
          opacity: 0.07,
          mixBlendMode: 'overlay',
          backgroundImage: GRAIN,
        }}
      />
      <Box sx={{ position: 'relative', zIndex: 1 }}>
        <BrandLogo
          url={logoUrl}
          alt="qnop"
          sx={{ height: 80, maxWidth: 420 }}
          fallback={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75 }}>
              <Box
                sx={{
                  width: 64,
                  height: 64,
                  borderRadius: 2.5,
                  bgcolor: 'rgba(255,255,255,0.12)',
                  border: '1px solid rgba(255,255,255,0.18)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <ShieldCheck size={36} />
              </Box>
              <Box>
                <Typography
                  sx={{ fontWeight: 700, fontSize: 30, letterSpacing: '-0.02em', lineHeight: 1 }}
                >
                  qnop
                </Typography>
                <Typography
                  sx={{
                    fontSize: 12.5,
                    letterSpacing: '0.12em',
                    textTransform: 'uppercase',
                    color: '#9BCEFA',
                    mt: 0.75,
                  }}
                >
                  Quality Notes · Sovereign
                </Typography>
              </Box>
            </Box>
          }
        />
      </Box>

      {/* Product stage: the gamified reviewer identity, before the first login. */}
      <Box
        sx={{
          position: 'relative',
          zIndex: 1,
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          py: 5,
          minHeight: 0,
        }}
      >
        <ShowcasePlayerCard />
      </Box>

      <Box sx={{ position: 'relative', zIndex: 1, maxWidth: 460 }}>
        <Typography
          sx={{
            fontSize: 12.5,
            letterSpacing: '0.12em',
            textTransform: 'uppercase',
            color: '#61B5F6',
            fontWeight: 600,
            mb: 2.5,
          }}
        >
          Sovereign document review
        </Typography>
        <Typography
          component="h2"
          sx={{
            color: '#fff',
            fontWeight: 700,
            fontSize: { md: 30, lg: 37 },
            lineHeight: 1.12,
            letterSpacing: '-0.025em',
            mb: 2.5,
          }}
        >
          Reviews your team actually wants to finish.
        </Typography>
        <Typography sx={{ color: '#B9C6D4', fontSize: 15.5, lineHeight: 1.65, maxWidth: 430 }}>
          Annotate, discuss and approve documents together — with streaks, scoreboards and profiles
          that celebrate every closed review. And your data never leaves your own infrastructure.
        </Typography>
        <Stack direction="row" sx={{ flexWrap: 'nowrap', gap: { md: 0.75, lg: 1.25 }, mt: 4 }}>
          {FEATURES.map(({ icon: Icon, label }) => (
            <Stack
              key={label}
              direction="row"
              sx={{
                alignItems: 'center',
                gap: { md: 0.625, lg: 0.875 },
                px: { md: 1, lg: 1.5 },
                py: 0.875,
                borderRadius: 999,
                bgcolor: 'rgba(255,255,255,0.08)',
                border: '1px solid rgba(255,255,255,0.12)',
                fontSize: { md: 10.5, lg: 12 },
                color: '#C6E3FC',
                whiteSpace: 'nowrap',
                flexShrink: 0,
              }}
            >
              <Icon size={13} />
              {label}
            </Stack>
          ))}
        </Stack>
      </Box>
    </Box>
  );
}
