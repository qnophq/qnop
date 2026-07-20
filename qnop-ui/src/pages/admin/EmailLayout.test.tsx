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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { buildTheme } from '../../theme/theme';
import { EmailLayout } from './EmailLayout';

function renderAt(path: string) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/email" element={<EmailLayout />}>
            <Route path="server" element={<div>server-child</div>} />
            <Route path="templates" element={<div>templates-child</div>} />
            <Route path="templates/:key" element={<div>editor-child</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

function tab(name: string): HTMLElement {
  return screen.getByRole('tab', { name });
}

describe('EmailLayout', () => {
  it('renders the Email header and both tabs, with Server active on its route', () => {
    renderAt('/admin/email/server');

    expect(screen.getByRole('heading', { level: 1, name: 'Email' })).toBeTruthy();
    expect(tab('Server').getAttribute('aria-selected')).toBe('true');
    expect(tab('Templates').getAttribute('aria-selected')).toBe('false');
    expect(screen.getByText('server-child')).toBeTruthy();
  });

  it('renders the tabs as real links so open-in-new-tab and copy-link work', () => {
    renderAt('/admin/email/server');

    expect(tab('Server').getAttribute('href')).toBe('/admin/email/server');
    expect(tab('Templates').getAttribute('href')).toBe('/admin/email/templates');
  });

  it('marks Templates active on the list route', () => {
    renderAt('/admin/email/templates');

    expect(tab('Templates').getAttribute('aria-selected')).toBe('true');
    expect(tab('Server').getAttribute('aria-selected')).toBe('false');
    expect(screen.getByText('templates-child')).toBeTruthy();
  });

  it('keeps Templates active and the Email header visible on the editor detail route', () => {
    renderAt('/admin/email/templates/auth.password_reset');

    expect(tab('Templates').getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('heading', { level: 1, name: 'Email' })).toBeTruthy();
    expect(screen.getByText('editor-child')).toBeTruthy();
  });

  it('navigates to the other tab on click', () => {
    renderAt('/admin/email/server');

    fireEvent.click(tab('Templates'));

    expect(screen.getByText('templates-child')).toBeTruthy();
    expect(tab('Templates').getAttribute('aria-selected')).toBe('true');
  });
});
