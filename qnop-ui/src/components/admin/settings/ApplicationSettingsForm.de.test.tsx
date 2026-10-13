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
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AdminSetting } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { renderWithProviders } from '../../../test/renderWithProviders';
import { ApplicationSettingsForm } from './ApplicationSettingsForm';

const settings: AdminSetting[] = [
  {
    key: 'general.application_name',
    value: 'qnop',
    type: 'STRING' as AdminSetting['type'],
    description: 'Display name of this qnop instance.',
    sensitive: false,
  },
  {
    key: 'tracking.enabled',
    value: 'false',
    type: 'BOOLEAN' as AdminSetting['type'],
    description: 'Master switch for usage tracking.',
    sensitive: false,
  },
  {
    key: 'tracking.provider',
    value: 'umami',
    type: 'ENUM' as AdminSetting['type'],
    description: 'Analytics backend.',
    sensitive: false,
    allowedValues: ['none', 'umami'],
  },
  {
    key: 'upload.document_max_file_size_mb',
    value: '50',
    type: 'INTEGER' as AdminSetting['type'],
    description: 'Largest document accepted.',
    sensitive: false,
  },
  {
    // Not (yet) in the catalog: renders with the humanised key and the server's description.
    key: 'general.future_flag',
    value: 'false',
    type: 'BOOLEAN' as AdminSetting['type'],
    description: 'A setting the backend added after this catalog.',
    sensitive: false,
  },
];

vi.mock('../../../api/hooks/useSettings', () => ({
  useSettings: () => ({ data: { settings }, isLoading: false, isError: false }),
  useUpdateSettings: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

/**
 * The settings form in German (issue #836): chrome and every catalogued setting translate; a
 * setting the catalog does not know yet falls back to its key and the server's description.
 */
describe('ApplicationSettingsForm in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('translates section titles, setting labels and descriptions, and the tracking status', () => {
    renderWithProviders(<ApplicationSettingsForm />);

    expect(screen.getByRole('heading', { name: 'Allgemein' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Nutzungsmessung' })).toBeInTheDocument();
    // The status line interpolates the backend's name inside German quotes.
    expect(
      screen.getByText(
        'Es wird nichts gemessen — „Umami“ ist konfiguriert, aber die Nutzungsmessung ist oben ausgeschaltet.',
      ),
    ).toBeInTheDocument();
    // Label and description come from admin:settings.fields, not the server's English.
    expect(screen.getByRole('textbox', { name: 'Anwendungsname' })).toBeInTheDocument();
    expect(screen.getByText('Anzeigename dieser qnop-Installation.')).toBeInTheDocument();
    expect(screen.queryByText('Display name of this qnop instance.')).not.toBeInTheDocument();
    expect(
      screen.getByText('Welches Analyse-Backend die weitergeleiteten Messwerte empfängt.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Keine ungespeicherten Änderungen.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verwerfen' })).toBeDisabled();
  });

  it('counts unsaved changes and validates in German', async () => {
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<ApplicationSettingsForm />);

    const size = screen.getByDisplayValue('50');
    await user.clear(size);
    await user.type(size, '5000');
    expect(screen.getByText('1 ungespeicherte Änderung.')).toBeInTheDocument();

    await user.clear(screen.getByDisplayValue('qnop'));
    await user.type(screen.getByRole('textbox', { name: 'Anwendungsname' }), 'Akte');
    expect(screen.getByText('2 ungespeicherte Änderungen.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Änderungen speichern' }));
    expect(
      await screen.findByText('Gib eine ganze Zahl zwischen 1 und 1024 ein.'),
    ).toBeInTheDocument();
  });

  it('falls back to the humanised key and the server description for an unknown setting', () => {
    renderWithProviders(<ApplicationSettingsForm />);

    expect(screen.getByText('Future flag')).toBeInTheDocument();
    expect(screen.getByText('A setting the backend added after this catalog.')).toBeInTheDocument();
  });
});
