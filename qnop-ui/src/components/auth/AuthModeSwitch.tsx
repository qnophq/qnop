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
import { Link as RouterLink } from 'react-router-dom';

type Mode = 'login' | 'register';

const SEGMENTS: { mode: Mode; label: string; to: string }[] = [
  { mode: 'login', label: 'Sign in', to: '/login' },
  { mode: 'register', label: 'Create account', to: '/register' },
];

/**
 * Segmented sign-in / create-account switch shown above the auth form, matching
 * the design prototype's two-button toggle. Each segment is a router link, so
 * the switch navigates between the `/login` and `/register` routes; the active
 * segment is raised on a white surface.
 */
export function AuthModeSwitch({ active }: { active: Mode }) {
  return (
    <Box
      sx={{
        display: 'flex',
        gap: 0.5,
        p: 0.5,
        mb: 3.5,
        borderRadius: 2,
        bgcolor: (t) => (t.palette.mode === 'dark' ? 'rgba(255,255,255,0.06)' : '#EEF2F7'),
      }}
    >
      {SEGMENTS.map(({ mode, label, to }) => {
        const isActive = mode === active;
        return (
          <Box
            key={mode}
            component={RouterLink}
            to={to}
            replace
            aria-current={isActive ? 'page' : undefined}
            sx={(theme) => ({
              flex: 1,
              height: 38,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: 1.5,
              fontSize: 13.5,
              fontWeight: 600,
              textDecoration: 'none',
              letterSpacing: '-0.01em',
              transition: 'all .15s',
              color: isActive ? 'text.primary' : 'text.secondary',
              bgcolor: isActive ? 'background.paper' : 'transparent',
              boxShadow:
                isActive && theme.palette.mode === 'light'
                  ? '0 1px 3px rgba(1,32,66,0.10)'
                  : 'none',
              '&:hover': { color: isActive ? 'text.primary' : 'text.primary' },
            })}
          >
            {label}
          </Box>
        );
      })}
    </Box>
  );
}
