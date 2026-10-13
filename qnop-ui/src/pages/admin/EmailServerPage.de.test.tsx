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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AdminSetting } from '../../api/generated';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { EmailServerPage } from './EmailServerPage';

const SETTINGS: AdminSetting[] = [
  {
    key: 'smtp.enabled',
    value: 'true',
    type: 'BOOLEAN',
    description: 'Master switch.',
    sensitive: false,
  },
  { key: 'smtp.host', value: '', type: 'STRING', description: 'Host.', sensitive: false },
  { key: 'smtp.port', value: '587', type: 'INTEGER', description: 'Port.', sensitive: false },
  {
    key: 'smtp.encryption',
    value: 'tls',
    type: 'ENUM',
    description: 'Encryption.',
    sensitive: false,
    allowedValues: ['none', 'starttls', 'tls'],
  },
];

vi.mock('../../api/hooks/useSettings', () => ({
  useSettings: () => ({ data: { settings: SETTINGS }, isLoading: false, isError: false }),
  useUpdateSettings: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

vi.mock('../../api/hooks/useMailTemplates', () => ({
  useSendTestEmail: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

function renderPage(ui: ReactNode) {
  return render(<ThemeProvider theme={buildTheme('light', 'de')}>{ui}</ThemeProvider>);
}

/** The SMTP server page in German (issue #836): cards, status, enum labels and the plural. */
describe('email server page in German', () => {
  beforeEach(async () => {
    useAuthStore.setState({ email: 'admin@example.com' });
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the cards, the derived status and the curated encryption label', () => {
    renderPage(<EmailServerPage />);

    expect(screen.getByRole('heading', { name: 'Verbindung' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Status & Versand' })).toBeInTheDocument();
    // Enabled without a host: the status is derived client-side from catalog keys.
    expect(screen.getByText('Unvollständig')).toBeInTheDocument();
    expect(screen.getByText('Implizites TLS')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Test senden' })).toBeInTheDocument();
    expect(screen.getByText('Keine ungespeicherten Änderungen.')).toBeInTheDocument();
  });

  it('counts unsaved changes with the German plural and validates in German', () => {
    renderPage(<EmailServerPage />);

    fireEvent.change(screen.getByLabelText('Port'), { target: { value: '70000' } });
    expect(screen.getByText('1 ungespeicherte Änderung.')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Änderungen speichern' }));
    expect(screen.getByText('Gib einen Port zwischen 1 und 65535 ein.')).toBeInTheDocument();
    expect(
      screen.getByText('Für den E-Mail-Versand ist ein Host erforderlich.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verwerfen' })).toBeInTheDocument();
  });
});
