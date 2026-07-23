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
import { alpha, useTheme } from '@mui/material/styles';
import { Link as RouterLink } from 'react-router-dom';
import { useAuthStore } from '../../../stores/authStore';

/**
 * A resolved @mention rendered inline in a comment/annotation body (issue #462): a highlighted pill
 * carrying the "@Name" link text, pointing at the mentioned person's profile — `/profile` for
 * yourself, `/users/{id}` otherwise (the route resolves by id or slug). Only appears in non-anonymous
 * reviews: mentions are never resolved server-side for anonymous ones, so no `mention:` token ever
 * reaches here to link a hidden identity.
 */
export function MentionLink({ userId, children }: { userId: string; children: ReactNode }) {
  const theme = useTheme();
  const selfId = useAuthStore((s) => s.userId);
  const to = userId === selfId ? '/profile' : `/users/${userId}`;
  return (
    <Box
      component={RouterLink}
      to={to}
      data-testid="mention-link"
      sx={{
        color: theme.qnop.brand.blue,
        bgcolor: alpha(theme.qnop.brand.blue, 0.1),
        borderRadius: '4px',
        px: '0.3em',
        fontWeight: 600,
        textDecoration: 'none',
        whiteSpace: 'nowrap',
        '&:hover': { bgcolor: alpha(theme.qnop.brand.blue, 0.18) },
        '&:focus-visible': {
          outline: 'none',
          boxShadow: theme.qnop.focusRing,
        },
      }}
    >
      {children}
    </Box>
  );
}
