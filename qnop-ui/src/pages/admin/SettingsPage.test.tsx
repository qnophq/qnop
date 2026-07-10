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
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { buildTheme } from '../../theme/theme';
import { SettingsPage } from './SettingsPage';

// SettingsPage is a thin composition wrapper: it delegates to
// ApplicationSettingsForm, whose own hooks are out of scope here. Stub the
// child so the test asserts only the page's own composition.
vi.mock('../../components/admin/settings/ApplicationSettingsForm', () => ({
  ApplicationSettingsForm: () => <div data-testid="application-settings-form" />,
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<SettingsPage />, { wrapper });

describe('SettingsPage', () => {
  it('renders the page header title and description', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Settings' })).toBeTruthy();
    expect(screen.getByText('Workspace, uploads, usage tracking and authentication.')).toBeTruthy();
  });

  it('delegates to the application settings form', () => {
    renderPage();

    expect(screen.getByTestId('application-settings-form')).toBeTruthy();
  });
});
