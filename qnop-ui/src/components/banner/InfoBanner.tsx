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
import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ArrowUpRight, Info, OctagonAlert, TriangleAlert, X } from 'lucide-react';
import type { InfoBanner as InfoBannerModel } from '../../api/generated';
import { tokens } from '../../theme/tokens';

/**
 * Tone per severity: the translucent badge tint (composites over both the light
 * card and the dark surface) plus the brighter accent that draws the edge.
 */
const TONES = {
  info: { tint: tokens.badge.blue, accent: tokens.brand.blue, Icon: Info },
  warning: { tint: tokens.badge.amber, accent: tokens.semantic.warning, Icon: TriangleAlert },
  critical: { tint: tokens.badge.red, accent: tokens.semantic.danger, Icon: OctagonAlert },
} as const;

type Severity = keyof typeof TONES;

interface InfoBannerProps {
  banner: InfoBannerModel;
  /**
   * `bar` spans a surface edge to edge (the app shell, under the top bar);
   * `card` is the rounded, inset form the auth screens use above their card.
   */
  variant?: 'bar' | 'card';
  /** Provided only where dismissing is offered — the auth screens do not. */
  onDismiss?: () => void;
}

/**
 * An operator's notice (issue #664), in one shape for both places it appears.
 *
 * <p>Deliberately not MUI's `Alert`: that reads as a *result* — something the
 * user just did went well or badly — and this is standing information about the
 * deployment. So it takes the house's own language instead: the badge tint the
 * rest of the app uses for state, one 3px accent edge, and nothing else. The
 * severity changes the tone and the icon and nothing about the geometry, so a
 * critical notice cannot rearrange the page it sits on.
 */
export function InfoBanner({ banner, variant = 'bar', onDismiss }: InfoBannerProps) {
  const theme = useTheme();
  const severity = (banner.severity ?? 'info') as Severity;
  const tone = TONES[severity] ?? TONES.info;
  const { Icon } = tone;
  const fg = theme.palette.mode === 'dark' ? tone.tint.fgDark : tone.tint.fg;
  const hasLink = Boolean(banner.linkLabel && banner.linkUrl);

  return (
    <Box
      // A critical notice interrupts; the other two are read when the user gets
      // to them. Both are announced, neither steals focus.
      role={severity === 'critical' ? 'alert' : 'status'}
      aria-live={severity === 'critical' ? 'assertive' : 'polite'}
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1.25,
        px: { xs: 1.75, sm: 2.25 },
        py: 1.15,
        color: fg,
        bgcolor: tone.tint.bg,
        borderLeft: `3px solid ${tone.accent}`,
        ...(variant === 'bar'
          ? { borderBottom: `1px solid ${tone.tint.border}` }
          : {
              border: `1px solid ${tone.tint.border}`,
              borderLeft: `3px solid ${tone.accent}`,
              borderRadius: `${tokens.radius.md}px`,
            }),
      }}
    >
      <Box aria-hidden sx={{ display: 'flex', color: tone.accent, pt: '2px', flexShrink: 0 }}>
        <Icon size={16} />
      </Box>
      <Typography
        variant="body2"
        sx={{ flex: 1, minWidth: 0, lineHeight: 1.45, fontWeight: 500, color: 'inherit' }}
      >
        {banner.message}
        {hasLink && (
          <Link
            href={banner.linkUrl}
            target="_blank"
            // The URL is operator-configured and validated as http(s) server-side;
            // the opener is still severed, because a banner shown to everyone is
            // the last place to leave a window handle lying around.
            rel="noopener noreferrer"
            sx={{
              color: 'inherit',
              textDecorationColor: 'currentColor',
              fontWeight: 600,
              ml: 1,
              whiteSpace: 'nowrap',
            }}
          >
            {banner.linkLabel}
            <ArrowUpRight size={13} style={{ verticalAlign: '-2px', marginLeft: 2 }} />
          </Link>
        )}
      </Typography>
      {onDismiss && (
        <IconButton
          size="small"
          onClick={onDismiss}
          aria-label="Dismiss this notice"
          sx={{ color: 'inherit', opacity: 0.7, mt: '-2px', '&:hover': { opacity: 1 } }}
        >
          <X size={15} />
        </IconButton>
      )}
    </Box>
  );
}
