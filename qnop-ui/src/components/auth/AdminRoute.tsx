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
import { selectIsAdmin, useAuthStore } from '../../stores/authStore';
import { ProtectedRoute } from './ProtectedRoute';

interface AdminRouteProps {
  children: ReactNode;
}

/**
 * Gate for admin-only routes. Requires authentication (via {@link
 * ProtectedRoute}) and the global ADMIN role; otherwise redirects to 403.
 */
export function AdminRoute({ children }: AdminRouteProps) {
  const isAdmin = useAuthStore(selectIsAdmin);

  return <ProtectedRoute>{isAdmin ? children : <Navigate to="/403" replace />}</ProtectedRoute>;
}
