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

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AdminRoute } from './AdminRoute';
import { useAuthStore } from '../../stores/authStore';

function renderAdminAt() {
  return render(
    <MemoryRouter initialEntries={['/admin']}>
      <Routes>
        <Route path="/login" element={<div>login page</div>} />
        <Route path="/403" element={<div>forbidden</div>} />
        <Route
          path="/admin"
          element={
            <AdminRoute>
              <div>admin content</div>
            </AdminRoute>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AdminRoute', () => {
  it('renders children for an ADMIN', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'ADMIN' });
    renderAdminAt();
    expect(screen.getByText('admin content')).toBeInTheDocument();
  });

  it('redirects a non-admin to 403', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'MEMBER' });
    renderAdminAt();
    expect(screen.getByText('forbidden')).toBeInTheDocument();
  });

  it('redirects an unauthenticated user to login', () => {
    useAuthStore.setState({ isAuthenticated: false, role: null });
    renderAdminAt();
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
});
