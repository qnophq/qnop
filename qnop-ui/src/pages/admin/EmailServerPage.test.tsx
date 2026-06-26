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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AdminSetting } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { EmailServerPage } from './EmailServerPage';

const { updateMutate, testMutate } = vi.hoisted(() => ({
  updateMutate: vi.fn(),
  testMutate: vi.fn(),
}));

const SETTINGS: AdminSetting[] = [
  // A non-SMTP key the page must ignore.
  {
    key: 'general.application_name',
    value: 'qnop',
    type: 'STRING',
    description: 'Display name.',
    sensitive: false,
  },
  {
    key: 'smtp.enabled',
    value: 'false',
    type: 'BOOLEAN',
    description: 'Master switch.',
    sensitive: false,
  },
  {
    key: 'smtp.host',
    value: 'smtp.example.com',
    type: 'STRING',
    description: 'Host.',
    sensitive: false,
  },
  { key: 'smtp.port', value: '587', type: 'INTEGER', description: 'Port.', sensitive: false },
  { key: 'smtp.username', value: '', type: 'STRING', description: 'Username.', sensitive: false },
  {
    key: 'smtp.password',
    value: '***',
    type: 'PASSWORD',
    description: 'Password.',
    sensitive: true,
  },
  {
    key: 'smtp.encryption',
    value: 'starttls',
    type: 'ENUM',
    description: 'Encryption.',
    sensitive: false,
    allowedValues: ['none', 'starttls', 'tls'],
  },
  { key: 'smtp.from', value: '', type: 'STRING', description: 'From.', sensitive: false },
  {
    key: 'smtp.from_name',
    value: 'qnop',
    type: 'STRING',
    description: 'From name.',
    sensitive: false,
  },
];

vi.mock('../../api/hooks/useSettings', () => ({
  useSettings: () => ({ data: { settings: SETTINGS }, isLoading: false, isError: false }),
  useUpdateSettings: () => ({ mutateAsync: updateMutate, isPending: false }),
}));

vi.mock('../../api/hooks/useMailTemplates', () => ({
  useSendTestEmail: () => ({ mutateAsync: testMutate, isPending: false }),
}));

beforeEach(() => {
  updateMutate.mockReset().mockResolvedValue({ settings: SETTINGS });
  testMutate.mockReset().mockResolvedValue({ status: 'SENT', detail: 'Sent.' });
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<EmailServerPage />, { wrapper });

describe('EmailServerPage', () => {
  it('renders the three sections and the encryption value with its curated label', () => {
    renderPage();

    expect(screen.getByText('Connection')).toBeTruthy();
    expect(screen.getByText('Identity')).toBeTruthy();
    expect(screen.getByText('Status & delivery')).toBeTruthy();
    // The raw enum value `starttls` surfaces as the curated label.
    expect(screen.getByText('STARTTLS')).toBeTruthy();
  });

  it('reflects the enabled toggle in the live status badge', () => {
    renderPage();

    // Master switch is off → Disabled.
    expect(screen.getByText('Disabled')).toBeTruthy();

    fireEvent.click(screen.getByRole('switch'));

    // Now enabled and a host is configured → Ready.
    expect(screen.getByText('Ready')).toBeTruthy();
  });

  it('blocks the test-send while there are unsaved changes', () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('Recipient'), {
      target: { value: 'qa@example.com' },
    });
    // No setting edits yet → test button is enabled.
    expect(screen.getByRole('button', { name: 'Send test' })).not.toBeDisabled();

    // Editing a setting makes the saved-state stale, so testing is blocked.
    fireEvent.change(screen.getByLabelText('From name'), { target: { value: 'qnop mailer' } });

    expect(screen.getByRole('button', { name: 'Send test' })).toBeDisabled();
    expect(screen.getByText('Save your changes before sending a test message.')).toBeTruthy();
  });

  it('patches only the changed keys on save', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('From name'), { target: { value: 'qnop mailer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({ 'smtp.from_name': 'qnop mailer' }),
    );
  });

  it('sends a test message to the entered recipient', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('Recipient'), {
      target: { value: 'qa@example.com' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send test' }));

    await waitFor(() => expect(testMutate).toHaveBeenCalledWith('qa@example.com'));
  });
});
