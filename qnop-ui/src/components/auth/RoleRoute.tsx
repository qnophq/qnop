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
import { Navigate } from 'react-router-dom';
import type { UserRole } from '../../api/generated';
import { useAuthStore } from '../../stores/authStore';
import { ProtectedRoute } from './ProtectedRoute';

interface RoleRouteProps {
  allow: UserRole[];
  children: ReactNode;
}

/**
 * Gate for routes restricted to a set of global roles. Requires authentication
 * (via {@link ProtectedRoute}); a signed-in user without an allowed role is
 * sent to 403. ({@link AdminRoute} is the ADMIN-only shorthand.)
 */
export function RoleRoute({ allow, children }: RoleRouteProps) {
  const role = useAuthStore((s) => s.role);

  return (
    <ProtectedRoute>
      {role && allow.includes(role) ? children : <Navigate to="/403" replace />}
    </ProtectedRoute>
  );
}
