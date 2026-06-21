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
import { RoleRoute } from './RoleRoute';
import { useAuthStore } from '../../stores/authStore';

function renderAt() {
  return render(
    <MemoryRouter initialEntries={['/compliance']}>
      <Routes>
        <Route path="/login" element={<div>login page</div>} />
        <Route path="/403" element={<div>forbidden</div>} />
        <Route
          path="/compliance"
          element={
            <RoleRoute allow={['ADMIN', 'AUDITOR']}>
              <div>compliance content</div>
            </RoleRoute>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RoleRoute', () => {
  it('renders children for an allowed role (AUDITOR)', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'AUDITOR' });
    renderAt();
    expect(screen.getByText('compliance content')).toBeInTheDocument();
  });

  it('renders children for ADMIN too', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'ADMIN' });
    renderAt();
    expect(screen.getByText('compliance content')).toBeInTheDocument();
  });

  it('sends a disallowed role (MEMBER) to 403', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'MEMBER' });
    renderAt();
    expect(screen.getByText('forbidden')).toBeInTheDocument();
  });

  it('sends an unauthenticated user to login', () => {
    useAuthStore.setState({ isAuthenticated: false, role: null });
    renderAt();
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
});
