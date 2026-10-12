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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { checkA11y } from '../../test/axe';
import { SidebarContent } from './SidebarContent';

vi.mock('../../api/hooks/useConfig', () => ({
  useConfig: () => ({ data: undefined }),
}));

function renderSidebar(collapsed = false) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>
          <SidebarContent collapsed={collapsed} />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('SidebarContent landmarks (issue #460)', () => {
  beforeEach(() => {
    useAuthStore.setState({ role: 'ADMIN', userId: 'u1', displayName: 'Ada' });
  });

  it('exposes the rail as a named navigation landmark', () => {
    renderSidebar();
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument();
  });

  it('has no accessibility violations expanded', async () => {
    const { container } = renderSidebar();
    expect(await checkA11y(container)).toHaveNoViolations();
  });

  it('has no accessibility violations collapsed', async () => {
    const { container } = renderSidebar(true);
    expect(await checkA11y(container)).toHaveNoViolations();
  });
});

describe('SidebarContent in German (issue #464)', () => {
  beforeEach(async () => {
    useAuthStore.setState({ role: 'ADMIN', userId: 'u1', displayName: 'Ada' });
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the navigation, group heading and role from the German catalog', () => {
    renderSidebar();
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(nav).toHaveTextContent('Meine Teams');
    expect(nav).toHaveTextContent('Nachrichten');
    expect(nav).toHaveTextContent('Einstellungen');
    expect(nav).toHaveTextContent('Speicherkonsistenz');
    expect(screen.getByText('Administrator')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Benutzermenü' })).toBeInTheDocument();
  });
});
