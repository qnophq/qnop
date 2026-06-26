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

interface PageHeaderProps {
  /** The page title — rendered as the single page-level h1. */
  title: string;
  /** One-line purpose under the title; accepts rich content for longer copy. */
  description?: ReactNode;
  /** An inline element next to the title, e.g. a status badge on a detail page. */
  titleAdornment?: ReactNode;
  /** Primary action(s) pinned to the top-right (top on mobile). */
  action?: ReactNode;
}

/**
 * The canonical page header for every admin surface: a single h1 at a fixed
 * 28px, an optional secondary description, an optional inline title adornment
 * (e.g. a detail-page status badge), and an optional right-aligned action slot.
 * Centralising this guarantees the heading hierarchy and rhythm stay identical
 * across Settings, Email, Users, Teams, OIDC, Branding and Mail templates.
 */
export function PageHeader({ title, description, titleAdornment, action }: PageHeaderProps) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={2}
      sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <Typography variant="h1" sx={{ fontSize: 28 }} noWrap={Boolean(titleAdornment)}>
            {title}
          </Typography>
          {titleAdornment}
        </Stack>
        {description && (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            {description}
          </Typography>
        )}
      </Box>
      {action && <Box sx={{ flexShrink: 0 }}>{action}</Box>}
    </Stack>
  );
}
