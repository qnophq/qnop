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
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Link as RouterLink } from 'react-router';
import { tokens } from '../../theme/tokens';

const fadeUp = {
  '@keyframes errorStateFadeUp': {
    from: { opacity: 0, transform: 'translateY(10px)' },
    to: { opacity: 1, transform: 'translateY(0)' },
  },
};

export interface ErrorAction {
  label: string;
  /** Router target; mutually exclusive with onClick. */
  to?: string;
  onClick?: () => void;
}

interface ErrorStateProps {
  /** The HTTP-ish code chip; omit for stateful pages like offline. */
  code?: string;
  title: string;
  message: string;
  /** The motif from {@code illustrations.tsx} - decorative, hidden from AT. */
  illustration: ReactNode;
  primaryAction?: ErrorAction;
  secondaryAction?: ErrorAction;
  /**
   * 'route' (default) for destinations you can navigate to (404, 403) -
   * plain content; 'alert' for states that interrupted the user (500, 503,
   * 429, offline), announced via role="alert".
   */
  tone?: 'route' | 'alert';
}

function ActionButton({ action, primary }: { action: ErrorAction; primary: boolean }) {
  const variant = primary ? ('contained' as const) : ('outlined' as const);
  return action.to ? (
    <Button variant={variant} component={RouterLink} to={action.to}>
      {action.label}
    </Button>
  ) : (
    <Button variant={variant} onClick={action.onClick}>
      {action.label}
    </Button>
  );
}

/**
 * The full-page error shell (issue #611): illustration, code chip, headline,
 * body and the next move - an error page is a failed roll, not a dead end, so
 * there is always at least one way onward. Entrance is the app's staggered
 * rise (the EmptyDashboard recipe); reduced-motion users get everything in
 * place, still. Shell-less contexts (router errorElement) centre themselves
 * the same way as inside the AppShell.
 */
export function ErrorState({
  code,
  title,
  message,
  illustration,
  primaryAction = { label: 'Back to dashboard', to: '/' },
  secondaryAction,
  tone = 'route',
}: ErrorStateProps) {
  const theme = useTheme();
  const stagger = (index: number) => ({
    ...fadeUp,
    animation: `errorStateFadeUp 0.45s ${theme.transitions.easing.easeOut} both`,
    animationDelay: `${index * 90}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  });

  return (
    <Box
      sx={{ display: 'grid', placeItems: 'center', minHeight: '60dvh', p: 2 }}
      data-testid="error-state"
    >
      <Stack
        spacing={2}
        role={tone === 'alert' ? 'alert' : undefined}
        sx={{ alignItems: 'center', textAlign: 'center', maxWidth: 440 }}
      >
        <Box aria-hidden sx={{ ...stagger(0), color: 'text.secondary' }}>
          {illustration}
        </Box>
        <Stack spacing={1} sx={{ ...stagger(1), alignItems: 'center' }}>
          {code && (
            <Typography
              component="span"
              sx={{
                fontFamily: tokens.font.mono,
                fontSize: '0.8rem',
                fontWeight: 700,
                letterSpacing: '0.08em',
                color: theme.qnop.brand.blue,
                bgcolor: alpha(theme.qnop.brand.blue, theme.qnop.mode === 'dark' ? 0.16 : 0.1),
                borderRadius: '999px',
                px: 1.25,
                py: 0.25,
              }}
            >
              {code}
            </Typography>
          )}
          <Typography variant="h1" sx={{ fontSize: '1.5rem', lineHeight: 1.25 }}>
            {title}
          </Typography>
          <Typography color="text.secondary">{message}</Typography>
        </Stack>
        <Stack direction="row" spacing={1.5} sx={{ ...stagger(2), pt: 0.5 }}>
          <ActionButton action={primaryAction} primary />
          {secondaryAction && <ActionButton action={secondaryAction} primary={false} />}
        </Stack>
      </Stack>
    </Box>
  );
}
