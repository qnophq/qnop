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
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';

interface SectionCardProps {
  /** An icon chip in the brand tint; omit for a plain titled section. */
  icon?: LucideIcon;
  title: string;
  /** Optional one-line purpose under the title. */
  description?: string;
  /** Optional element pinned to the top-right of the header (e.g. a status badge). */
  action?: ReactNode;
  /** Drops the outlined card chrome — for hosts that bring their own edge (e.g. a drawer). */
  frameless?: boolean;
  children: ReactNode;
}

/**
 * The canonical content section across all admin surfaces: an outlined card with
 * a consistent header — an optional brand-tint icon chip, an h2 title, an optional
 * description and an optional right-aligned action — over its body. Using one
 * component everywhere is what keeps Settings, Email/SMTP, OIDC and the rest
 * speaking the same visual language. The icon chip is decorative (aria-hidden);
 * the title renders as a real h2 so the page keeps a clean h1 -> h2 hierarchy.
 */
export function SectionCard({
  icon: Icon,
  title,
  description,
  action,
  frameless = false,
  children,
}: SectionCardProps) {
  const theme = useTheme();
  return (
    <Paper
      variant={frameless ? 'elevation' : 'outlined'}
      elevation={0}
      sx={{ p: { xs: 2, sm: 3 }, ...(frameless && { bgcolor: 'transparent' }) }}
    >
      <Stack direction="row" spacing={1.5} sx={{ mb: 2.5, alignItems: 'flex-start' }}>
        {Icon && (
          <Box
            aria-hidden
            sx={{
              display: 'grid',
              placeItems: 'center',
              width: 38,
              height: 38,
              borderRadius: 2,
              flexShrink: 0,
              bgcolor: theme.qnop.badge.blue.bg,
              color: theme.qnop.brand.blue,
            }}
          >
            <Icon size={19} />
          </Box>
        )}
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h2" sx={{ fontSize: 17 }}>
            {title}
          </Typography>
          {description && (
            <Typography color="text.secondary" sx={{ fontSize: 13.5, mt: 0.25 }}>
              {description}
            </Typography>
          )}
        </Box>
        {action && <Box sx={{ flexShrink: 0 }}>{action}</Box>}
      </Stack>
      {children}
    </Paper>
  );
}
