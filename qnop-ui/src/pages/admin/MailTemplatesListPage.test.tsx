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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MailTemplateResponse } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { MailTemplatesListPage } from './MailTemplatesListPage';

const { navigateMock } = vi.hoisted(() => ({ navigateMock: vi.fn() }));

const TEMPLATES: MailTemplateResponse[] = [
  {
    key: 'auth.password_reset',
    friendlyName: 'Password reset',
    locale: 'en',
    subject: 'Reset your {{siteName}} password',
    bodyPlain: 'Hi {{recipientName}}',
    source: 'DATABASE',
    placeholders: ['actionUrl', 'recipientName', 'siteName'],
    defaultSubject: 'Reset your {{siteName}} password',
    defaultBodyPlain: 'Hi {{recipientName}}',
    updatedAt: new Date().toISOString(),
    updatedByName: 'Ada Admin',
  },
  {
    key: 'auth.registration_verification',
    friendlyName: 'Account verification',
    locale: 'en',
    subject: 'Verify your {{siteName}} account',
    bodyPlain: 'Welcome {{recipientName}}',
    source: 'DEFAULT',
    placeholders: ['actionUrl', 'recipientName', 'siteName'],
    defaultSubject: 'Verify your {{siteName}} account',
    defaultBodyPlain: 'Welcome {{recipientName}}',
  },
];

vi.mock('../../api/hooks/useMailTemplates', () => ({
  useMailTemplates: () => ({
    data: { templates: TEMPLATES },
    isLoading: false,
    isFetching: false,
    isError: false,
  }),
  useSendTestEmail: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigateMock,
}));

beforeEach(() => navigateMock.mockReset());

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

describe('MailTemplatesListPage', () => {
  it('renders a row per template with friendly name, key and subject', () => {
    render(<MailTemplatesListPage />, { wrapper });

    expect(screen.getByText('Password reset')).toBeTruthy();
    expect(screen.getByText('auth.password_reset')).toBeTruthy();
    expect(screen.getByText('Reset your {{siteName}} password')).toBeTruthy();
    expect(screen.getByText('Account verification')).toBeTruthy();
  });

  it('shows the template language as a locale badge', () => {
    render(<MailTemplatesListPage />, { wrapper });

    expect(screen.getByText('Language')).toBeTruthy();
    expect(screen.getAllByText('EN')).toHaveLength(2);
  });

  it('attributes a customised template and marks a built-in as a default', () => {
    render(<MailTemplatesListPage />, { wrapper });

    expect(screen.getByText('just now')).toBeTruthy();
    expect(screen.getByText('by Ada Admin')).toBeTruthy();
    expect(screen.getByText('Custom')).toBeTruthy();
    expect(screen.getByText('Default')).toBeTruthy();
  });

  it('navigates to the editor when a row is clicked', () => {
    render(<MailTemplatesListPage />, { wrapper });

    fireEvent.click(screen.getByText('Password reset'));

    expect(navigateMock).toHaveBeenCalledWith('/admin/email/templates/auth.password_reset');
  });

  it('navigates to the editor when a focused row is activated with the keyboard', () => {
    render(<MailTemplatesListPage />, { wrapper });

    fireEvent.keyDown(screen.getByText('Account verification').closest('tr')!, { key: 'Enter' });

    expect(navigateMock).toHaveBeenCalledWith(
      '/admin/email/templates/auth.registration_verification',
    );
  });
});
