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

import { useEffect, useRef, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '../../stores/authStore';

interface AuthHydrationBoundaryProps {
  children: ReactNode;
}

/**
 * Restores the session on first load by refreshing the access token from the
 * HttpOnly cookie, and blocks the app render until that settles so guarded
 * routes never flash the login page for an already-authenticated user. On an
 * authenticated → unauthenticated transition it clears the query cache so one
 * user's cached data never bleeds into the next.
 */
export function AuthHydrationBoundary({ children }: AuthHydrationBoundaryProps) {
  const isHydrating = useAuthStore((s) => s.isHydrating);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const hydrate = useAuthStore((s) => s.hydrate);
  const queryClient = useQueryClient();
  const wasAuthenticated = useRef(false);

  useEffect(() => {
    void hydrate();
  }, [hydrate]);

  useEffect(() => {
    if (isHydrating) {
      return;
    }
    if (wasAuthenticated.current && !isAuthenticated) {
      queryClient.clear();
    }
    wasAuthenticated.current = isAuthenticated;
  }, [isAuthenticated, isHydrating, queryClient]);

  if (isHydrating) {
    return (
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '100dvh',
        }}
      >
        <CircularProgress />
      </Box>
    );
  }
  return <>{children}</>;
}
