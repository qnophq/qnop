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

import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Link as RouterLink } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { UserAvatar } from '../shell/UserAvatar';

interface PersonLinkProps {
  /**
   * The person's REAL user id — absent for pseudonymised identities
   * (issue #413), which then render as a plain avatar+name without a link.
   */
  userId?: string | null;
  /**
   * The person's profile slug (issue #486) — preferred over the id for the
   * link target, absent under the same anonymity rule.
   */
  slug?: string | null;
  name: string;
  /** Server-built avatar URL (null when none is uploaded). */
  avatarUrl?: string | null;
  size?: number;
  /** Drops the name — an avatar-only variant for tight rows. */
  avatarOnly?: boolean;
}

/**
 * A person as the product shows people (issue #454): avatar and bold name in
 * one unit, linking to the profile — `/profile` for yourself, the colleague's
 * `/users/{slug}` page otherwise (falling back to the id for accounts without
 * a slug). Pseudonymised identities stay unlinked and initials-only, so
 * anonymity never leaks through a click.
 */
export function PersonLink({
  userId,
  slug,
  name,
  avatarUrl,
  size = 26,
  avatarOnly = false,
}: PersonLinkProps) {
  const theme = useTheme();
  const selfId = useAuthStore((s) => s.userId);
  const to = userId ? (userId === selfId ? '/profile' : `/users/${slug ?? userId}`) : null;

  const body = (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
      <UserAvatar name={name} size={size} imageUrl={avatarUrl ?? null} />
      {!avatarOnly && (
        <Typography
          component="span"
          noWrap
          sx={{ fontWeight: 700, fontSize: '0.85rem', color: 'text.primary' }}
        >
          {name}
        </Typography>
      )}
    </Stack>
  );

  if (!to) return body;
  return (
    <Stack
      component={RouterLink}
      to={to}
      onClick={(event) => event.stopPropagation()}
      direction="row"
      sx={{
        alignItems: 'center',
        minWidth: 0,
        textDecoration: 'none',
        borderRadius: '6px',
        '&:hover': {
          bgcolor: alpha(theme.qnop.brand.blue, 0.08),
          '& .MuiTypography-root': { color: theme.qnop.brand.blue },
        },
      }}
      aria-label={`View ${name}'s profile`}
    >
      {body}
    </Stack>
  );
}
