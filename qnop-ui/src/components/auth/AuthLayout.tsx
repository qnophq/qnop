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
import { useTheme } from '@mui/material/styles';
import { ShieldCheck } from 'lucide-react';
import { useConfig } from '../../api/hooks/useConfig';
import { BrandLogo } from '../branding/BrandLogo';
import { BrandPanel } from './BrandPanel';

interface AuthLayoutProps {
  title: string;
  subtitle?: string;
  /** Optional slot rendered above the title (e.g. the sign-in/register switch). */
  headerSlot?: ReactNode;
  children: ReactNode;
}

/**
 * Compact brand header for viewports where the brand panel is hidden (below
 * `md`): the tenant logo (theme-appropriate variant) or the qnop mini wordmark,
 * so mobile users don't land on an unbranded form (#503).
 */
function MobileBrandHeader() {
  const dark = useTheme().palette.mode === 'dark';
  const branding = useConfig().data?.branding;
  const logoUrl = dark ? branding?.logoDark.url : branding?.logoLight.url;
  return (
    <Box sx={{ display: { xs: 'flex', md: 'none' }, justifyContent: 'center', mb: 4 }}>
      <BrandLogo
        url={logoUrl}
        alt="qnop"
        sx={{ height: 32, maxWidth: 200 }}
        fallback={
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Box
              sx={{
                width: 32,
                height: 32,
                borderRadius: 1.25,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'primary.main',
                bgcolor: (t) =>
                  t.palette.mode === 'dark' ? 'rgba(18,144,239,0.16)' : 'rgba(18,144,239,0.10)',
                border: '1px solid rgba(18,144,239,0.28)',
              }}
            >
              <ShieldCheck size={19} />
            </Box>
            <Typography sx={{ fontWeight: 700, fontSize: 17, letterSpacing: '-0.02em' }}>
              qnop
            </Typography>
          </Box>
        }
      />
    </Box>
  );
}

/**
 * Two-column shell for the public auth screens (#103, #503): the sovereign
 * brand panel with the player-card showcase on the left (hidden below `md`,
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
          alignItems: 'center',
          justifyContent: 'center',
          p: { xs: 3, sm: 5 },
          bgcolor: 'background.default',
        }}
      >
        <Box sx={{ width: '100%', maxWidth: 408 }}>
          <MobileBrandHeader />
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
  );
}
