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

/**
 * A GitHub-style @mention rendered inline in a comment/annotation body (issue #462): a highlighted
 * pill carrying the "@slug" text, pointing at the mentioned person's public profile (the route
 * resolves the slug case-insensitively; an unknown slug lands on the anti-enumeration 404).
 */
export function MentionLink({ slug, children }: { slug: string; children: ReactNode }) {
  const theme = useTheme();
  const to = `/users/${slug}`;
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
