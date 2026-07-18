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

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AdminSetting } from '../../../api/generated';
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
    key: 'general.default_timezone',
    value: 'Europe/Berlin',
    type: 'STRING' as AdminSetting['type'],
    description: 'Default display timezone.',
    sensitive: false,
  },
];

vi.mock('../../../api/hooks/useSettings', () => ({
  useSettings: () => ({ data: { settings }, isLoading: false, isError: false }),
  useUpdateSettings: () => ({ mutate: vi.fn(), isPending: false }),
}));

describe('ApplicationSettingsForm — default timezone', () => {
  it('renders the default timezone as a searchable dropdown, not a text field', () => {
    renderWithProviders(<ApplicationSettingsForm />);

    // The timezone control is an Autocomplete (role combobox), pre-set to the stored zone…
    const zone = screen.getByRole('combobox', { name: /default timezone/i });
    expect(zone).toHaveValue('Europe/Berlin');
    // …while a plain string setting stays a free-text field.
    expect(screen.getByRole('textbox', { name: /application name/i })).toBeInTheDocument();
  });

  it('lists known zones with their offsets when opened', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ApplicationSettingsForm />);

    await user.click(screen.getByRole('combobox', { name: /default timezone/i }));
    expect(await screen.findByRole('option', { name: /Asia\/Tokyo/ })).toBeInTheDocument();
  });
});
