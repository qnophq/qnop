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
import { useUserProfile } from '../../../api/hooks/useUsers';
import { UserAvatar } from '../../shell/UserAvatar';

/** Inline avatar size, tuned to the 0.875rem body text of the comment bubbles. */
const AVATAR_SIZE = 16;

/**
 * An @mention rendered inline in a comment/annotation body (issue #462): a highlighted pill
 * pointing at the mentioned person's public profile (the route resolves the slug
 * case-insensitively; an unknown slug lands on the anti-enumeration 404). The raw Markdown keeps
 * the immutable @slug token; for display the slug resolves — through the profile cache shared
 * with the profile page and hover card — into the person's avatar and current display name.
 * Until it resolves, or for a slug the workspace cannot resolve, the raw @slug stays readable.
 */
export function MentionLink({ slug, children }: { slug: string; children: ReactNode }) {
  const theme = useTheme();
  const profile = useUserProfile(slug).data;
  return (
    <Box
      component={RouterLink}
      to={`/users/${slug}`}
      data-testid="mention-link"
      sx={{
        color: theme.qnop.brand.blue,
        bgcolor: alpha(theme.qnop.brand.blue, 0.1),
        borderRadius: '999px',
        px: '0.4em',
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
      {profile ? (
        <>
          <Box
            component="span"
            sx={{ display: 'inline-flex', verticalAlign: 'text-bottom', mr: '0.3em' }}
          >
            <UserAvatar
              component="span"
              name={profile.displayName}
              size={AVATAR_SIZE}
              imageUrl={profile.avatarUrl ?? null}
            />
          </Box>
          {profile.displayName}
        </>
      ) : (
        children
      )}
    </Box>
  );
}
