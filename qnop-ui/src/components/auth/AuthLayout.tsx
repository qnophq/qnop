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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Fingerprint, Server, ShieldCheck } from 'lucide-react';
import { tokens } from '../../theme/tokens';

const TRUST = [
  { icon: Server, label: 'On-Premises · Frankfurt' },
  { icon: ShieldCheck, label: 'BSI C5 · ISO 27001' },
  { icon: Fingerprint, label: 'AES-256' },
];

interface AuthLayoutProps {
  title: string;
  subtitle?: string;
  children: ReactNode;
}

/**
 * Two-column shell for the public auth screens (#103): a sovereign-themed brand
 * panel on the left (hidden below `md`) and the form card on the right, matching
 * the design prototype's "Dokumenten-Review, souverän" treatment.
 */
export function AuthLayout({ title, subtitle, children }: AuthLayoutProps) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: '1.05fr 1fr' },
      }}
    >
      {/* Brand panel */}
      <Box
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          position: 'relative',
          overflow: 'hidden',
          color: '#fff',
          p: 7,
          background: `linear-gradient(155deg, ${tokens.brand.navy} 0%, ${tokens.brand.navy700} 60%, #034079 100%)`,
        }}
      >
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

        <Box
          sx={{ position: 'relative', zIndex: 1, display: 'flex', alignItems: 'center', gap: 1.25 }}
        >
          <Box
            sx={{
              width: 38,
              height: 38,
              borderRadius: 1.5,
              bgcolor: 'rgba(255,255,255,0.12)',
              border: '1px solid rgba(255,255,255,0.18)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <ShieldCheck size={22} />
          </Box>
          <Box>
            <Typography
              sx={{ fontWeight: 700, fontSize: 17, letterSpacing: '-0.02em', lineHeight: 1 }}
            >
              qnop
            </Typography>
            <Typography
              sx={{
                fontSize: 10.5,
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                color: '#9BCEFA',
                mt: 0.4,
              }}
            >
              Quality Notes · Sovereign
            </Typography>
          </Box>
        </Box>

        <Box sx={{ position: 'relative', zIndex: 1, mt: 'auto', maxWidth: 460 }}>
          <Typography
            sx={{
              fontSize: 12,
              letterSpacing: '0.1em',
              textTransform: 'uppercase',
              color: '#61B5F6',
              fontWeight: 500,
              mb: 2,
            }}
          >
            Dokumenten-Review, souverän
          </Typography>
          <Typography variant="h2" sx={{ color: '#fff', mb: 2 }}>
            Prüfen, freigeben, vertrauen — alles an einem Ort.
          </Typography>
          <Typography sx={{ color: '#B9C6D4', lineHeight: 1.6, maxWidth: 420 }}>
            Verträge gemeinsam prüfen, Versionen vergleichen und Reviews koordinieren. Ihre Daten
            bleiben dabei vollständig auf Ihrer Infrastruktur.
          </Typography>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1.25, mt: 4 }}>
            {TRUST.map(({ icon: Icon, label }) => (
              <Stack
                key={label}
                direction="row"
                sx={{
                  alignItems: 'center',
                  gap: 0.875,
                  px: 1.5,
                  py: 0.875,
                  borderRadius: 999,
                  bgcolor: 'rgba(255,255,255,0.08)',
                  border: '1px solid rgba(255,255,255,0.12)',
                  fontSize: 12,
                  color: '#C6E3FC',
                }}
              >
                <Icon size={14} />
                {label}
              </Stack>
            ))}
          </Stack>
        </Box>

        <Typography sx={{ position: 'relative', zIndex: 1, mt: 5, fontSize: 12, color: '#7A8BA0' }}>
          © 2026 devtank42 GmbH · Alle Daten verbleiben in der EU
        </Typography>
      </Box>

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
          <Typography variant="h4" component="h1" sx={{ mb: subtitle ? 0.5 : 3 }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography color="text.secondary" sx={{ mb: 3 }}>
              {subtitle}
            </Typography>
          )}
          {children}
        </Box>
      </Box>
    </Box>
  );
}
