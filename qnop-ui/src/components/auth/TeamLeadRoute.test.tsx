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
import { TeamLeadRoute } from './TeamLeadRoute';
import { useAuthStore } from '../../stores/authStore';

function renderAt() {
  return render(
    <MemoryRouter initialEntries={['/my-teams']}>
      <Routes>
        <Route path="/login" element={<div>login page</div>} />
        <Route path="/403" element={<div>forbidden</div>} />
        <Route
          path="/my-teams"
          element={
            <TeamLeadRoute>
              <div>my teams content</div>
            </TeamLeadRoute>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe('TeamLeadRoute', () => {
  it('renders children for a team lead (non-admin)', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'MEMBER', teamLead: true });
    renderAt();
    expect(screen.getByText('my teams content')).toBeInTheDocument();
  });

  it('renders children for an admin even without leading a team', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'ADMIN', teamLead: false });
    renderAt();
    expect(screen.getByText('my teams content')).toBeInTheDocument();
  });

  it('sends a plain member who leads no team to 403', () => {
    useAuthStore.setState({ isAuthenticated: true, role: 'MEMBER', teamLead: false });
    renderAt();
    expect(screen.getByText('forbidden')).toBeInTheDocument();
  });

  it('sends an unauthenticated user to login', () => {
    useAuthStore.setState({ isAuthenticated: false, role: null, teamLead: false });
    renderAt();
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
});
