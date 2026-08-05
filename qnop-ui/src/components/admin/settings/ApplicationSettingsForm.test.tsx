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
  {
    // The opt-in that loosens a privacy floor (issue #712).
    key: 'tracking.forward_client_ip',
    value: 'full',
    type: 'ENUM' as AdminSetting['type'],
    description: 'What reaches the backend as the visitor address.',
    sensitive: false,
    allowedValues: ['anonymized', 'none', 'full'],
  },
  {
    // Governed by a capability the operator withheld (issue #681): shown,
    // because the ceiling is the answer to "why was my file refused", but not
    // editable here.
    key: 'upload.document_max_file_size_mb',
    value: '50',
    type: 'INTEGER' as AdminSetting['type'],
    description: 'Largest document accepted.',
    sensitive: false,
    editable: false,
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

  // Budget and `delay: null` as in TimezoneSetting.test.tsx: opening the picker
  // renders ~418 zones, and resolving one by accessible name costs ~1.1 s in
  // jsdom — a query cost, not a production one (issue #716).
  it('lists known zones with their offsets when opened', async () => {
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<ApplicationSettingsForm />);

    await user.click(screen.getByRole('combobox', { name: /default timezone/i }));
    const tokyo = await screen.findByRole('option', { name: /Asia\/Tokyo/ });

    // The row carries the zone AND its offset — the two halves the option
    // renders. Asserted explicitly because #716 rebuilt this markup from
    // Box/Typography with `sx` into hoisted `styled` elements.
    expect(tokyo).toHaveTextContent('Asia/Tokyo');
    expect(tokyo).toHaveTextContent(/GMT[+-]\d/);
  }, 15_000);

  it('renders a governed setting read-only and says who set it', () => {
    renderWithProviders(<ApplicationSettingsForm />);

    expect(screen.getByDisplayValue('50')).toBeDisabled();
    expect(screen.getByText(/Set by this deployment/)).toBeInTheDocument();
    // Ungoverned settings are untouched — the form is not read-only wholesale.
    expect(screen.getByRole('textbox', { name: /application name/i })).toBeEnabled();
  });

  it('warns where the operator chose to forward exact addresses (#712)', () => {
    renderWithProviders(<ApplicationSettingsForm />);

    // Said where the choice is made, not only in a document nobody has open —
    // and it names the duties rather than second-guessing the decision.
    const warning = screen.getByText(/Exact addresses are personal data/);
    expect(warning).toBeInTheDocument();
    expect(screen.getByText(/legal basis/)).toBeInTheDocument();
    // The other gates are explicitly said to survive, which is the promise the
    // opt-in was granted on.
    expect(screen.getByText(/Do-Not-Track keep applying/)).toBeInTheDocument();
  });
});
