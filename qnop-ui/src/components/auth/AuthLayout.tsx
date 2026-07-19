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
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { keyframes } from '@mui/material/styles';
import { ShieldCheck } from 'lucide-react';
import { useConfig } from '../../api/hooks/useConfig';
import { BrandLogo } from '../branding/BrandLogo';
import { BrandPanel } from './BrandPanel';
import { tokens } from '../../theme/tokens';
import authBg from '../../assets/auth/auth-bg.webp';
import authDocs from '../../assets/auth/auth-docs.svg';

/** Slow bob for the corner document illustration (it carries its own tilt). */
const floatDocs = keyframes`
  from { transform: translateY(0); }
  to   { transform: translateY(-12px); }
`;

interface AuthLayoutProps {
  title: string;
  subtitle?: string;
  /** Optional slot rendered above the title (e.g. the sign-in/register switch). */
  headerSlot?: ReactNode;
  children: ReactNode;
}

/**
 * Full-bleed brand band for viewports where the brand panel is hidden (below
 * `md`): the sovereign navy atmosphere with the calm document backdrop and a
 * large tenant logo, so mobile users get the same brand moment as the desktop
 * stage instead of an unbranded form (#503). The band is always navy, so it
 * uses the dark-surface logo variant regardless of theme.
 */
function MobileBrandBand() {
  const logoUrl = useConfig().data?.branding?.logoDark.url;
  // The band's backgrounds fade out through a mask instead of ending on a hard
  // edge, so the navy atmosphere dissolves into whatever the theme's page
  // background is — no seam between brand band and form.
  const fadeMask = 'linear-gradient(to bottom, #000 40%, transparent 96%)';
  return (
    <Box
      sx={{
        display: { xs: 'flex', md: 'none' },
        position: 'relative',
        overflow: 'hidden',
        minHeight: { xs: 200, sm: 232 },
        alignItems: 'center',
        justifyContent: 'center',
        color: '#fff',
        pb: 4.5,
      }}
    >
      <Box
        aria-hidden
        sx={{ position: 'absolute', inset: 0, maskImage: fadeMask, WebkitMaskImage: fadeMask }}
      >
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            background: `linear-gradient(155deg, ${tokens.brand.navy} 0%, ${tokens.brand.navy700} 60%, #034079 100%)`,
          }}
        />
        <Box
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
            opacity: 0.65,
          }}
        />
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            background: 'linear-gradient(to bottom, rgba(1,22,44,0.3) 0%, transparent 45%)',
          }}
        />
      </Box>
      <Box
        sx={{
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 1.5,
          px: 3,
        }}
      >
        <BrandLogo
          url={logoUrl}
          alt="qnop"
          sx={{ height: { xs: 56, sm: 64 }, maxWidth: 300 }}
          fallback={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <Box
                sx={{
                  width: 56,
                  height: 56,
                  borderRadius: 2,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  bgcolor: 'rgba(255,255,255,0.12)',
                  border: '1px solid rgba(255,255,255,0.18)',
                }}
              >
                <ShieldCheck size={32} />
              </Box>
              <Typography sx={{ fontWeight: 700, fontSize: 28, letterSpacing: '-0.02em' }}>
                qnop
              </Typography>
            </Box>
          }
        />
        <Typography
          sx={{
            fontSize: 11,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            color: '#9BCEFA',
            fontWeight: 600,
          }}
        >
          Sovereign document review
        </Typography>
      </Box>
    </Box>
  );
}

/**
 * Two-column shell for the public auth screens (#103, #503): the sovereign
 * brand panel with the review-stage showcase on the left (hidden below `md`,
 * replaced by a compact brand header above the form) and the form card on the
 * right.
 */
export function AuthLayout({ title, subtitle, headerSlot, children }: AuthLayoutProps) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: '1.05fr 1fr' },
      }}
    >
      <BrandPanel />

      {/* Form panel */}
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          bgcolor: 'background.default',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        <MobileBrandBand />
        {/* decorative corner accent: the annotated-document motif from the logomark */}
        <Box
          aria-hidden
          component="img"
          src={authDocs}
          alt=""
          sx={{
            position: 'absolute',
            right: { xs: -34, sm: -40 },
            bottom: { xs: -56, sm: -54 },
            width: { xs: 130, sm: 200 },
            // On phones the form can reach into the corner (outlined buttons are
            // transparent), so the accent steps back to a subtle watermark.
            opacity: { xs: 0.45, sm: 1 },
            pointerEvents: 'none',
            filter: 'drop-shadow(0 18px 30px rgba(0,0,0,0.30))',
            animation: `${floatDocs} 7s ease-in-out 0.8s infinite alternate`,
            '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
          }}
        />
        <Box
          sx={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            p: { xs: 3, sm: 5 },
          }}
        >
          <Box sx={{ width: '100%', maxWidth: 408, position: 'relative', zIndex: 1 }}>
            {headerSlot}
            <Typography
              component="h1"
              sx={{
                fontWeight: 700,
                fontSize: 26,
                letterSpacing: '-0.02em',
                lineHeight: 1.15,
                mb: subtitle ? 0.75 : 3,
              }}
            >
              {title}
            </Typography>
            {subtitle && (
              <Typography color="text.secondary" sx={{ fontSize: 14.5, mb: 3 }}>
                {subtitle}
              </Typography>
            )}
            {children}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
