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

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { CalendarDays } from 'lucide-react';
import { Navigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { PublicUserProfile } from '../api/generated';
import { usersApi } from '../api/config';
import { useAuthStore } from '../stores/authStore';
import { UserAvatar } from '../components/shell/UserAvatar';

const SINCE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

/**
 * A colleague's workspace-public profile (issue #454): the person behind the
 * avatars and names linked across the dashboards — display name, picture and
 * tenure, deliberately nothing more. Your own id redirects to the full
 * {@code /profile}.
 */
export function UserProfilePage() {
  const theme = useTheme();
  const { userId } = useParams<{ userId: string }>();
  const selfId = useAuthStore((s) => s.userId);
  const profileQuery = useQuery<PublicUserProfile>({
    queryKey: ['users', 'public-profile', userId],
    queryFn: async () => {
      const response = await usersApi.getUserProfile({ userId: userId as string });
      return response.data;
    },
    enabled: Boolean(userId) && userId !== selfId,
  });

  if (userId && userId === selfId) {
    return <Navigate to="/profile" replace />;
  }
  if (profileQuery.isPending) {
    return <Skeleton variant="rounded" height={220} sx={{ maxWidth: 560 }} />;
  }
  if (profileQuery.isError || !profileQuery.data) {
    return <Alert severity="error">This user does not exist.</Alert>;
  }

  const profile = profileQuery.data;
  return (
    <Stack spacing={0} sx={{ maxWidth: 560 }}>
      {/* A soft identity band — the avatar palette's tint as atmosphere. */}
      <Box
        sx={{
          height: 84,
          borderRadius: '14px 14px 0 0',
          background: `linear-gradient(120deg, ${alpha(theme.qnop.brand.blue, 0.16)}, ${alpha(
            theme.qnop.brand.blue,
            0.04,
          )})`,
          border: '1px solid',
          borderBottom: 'none',
          borderColor: 'divider',
        }}
      />
      <Stack
        spacing={1.5}
        sx={{
          px: 3,
          pb: 3,
          border: '1px solid',
          borderTop: 'none',
          borderColor: 'divider',
          borderRadius: '0 0 14px 14px',
        }}
      >
        <Box sx={{ mt: -4.5 }}>
          <Box
            sx={{
              display: 'inline-flex',
              borderRadius: '50%',
              border: '4px solid',
              borderColor: 'background.paper',
            }}
          >
            <UserAvatar name={profile.displayName} size={76} imageUrl={profile.avatarUrl ?? null} />
          </Box>
        </Box>
        <Box>
          <Typography variant="h2" sx={{ fontSize: '1.4rem' }}>
            {profile.displayName}
          </Typography>
          <Stack
            direction="row"
            spacing={0.75}
            sx={{ alignItems: 'center', color: 'text.secondary', mt: 0.5 }}
          >
            <CalendarDays size={14} aria-hidden />
            <Typography variant="body2">
              Member since {SINCE_FORMAT.format(new Date(profile.createdAt))}
            </Typography>
          </Stack>
        </Box>
      </Stack>
    </Stack>
  );
}
