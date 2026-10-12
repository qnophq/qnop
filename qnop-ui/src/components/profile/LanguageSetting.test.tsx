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
import { fireEvent, screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import { LanguageSetting } from './LanguageSetting';

describe('LanguageSetting', () => {
  it('names the inherited language when following the default', () => {
    renderWithProviders(
      <LanguageSetting explicit={undefined} inherited="de" saving={false} onChange={vi.fn()} />,
    );
    expect(screen.getByRole('combobox')).toHaveTextContent('System default (Deutsch)');
    expect(screen.getByText('Currently following the workspace default.')).toBeInTheDocument();
  });

  it('reports a picked language, and an empty value to follow the default again', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <LanguageSetting explicit="en" inherited="en" saving={false} onChange={onChange} />,
    );

    fireEvent.mouseDown(screen.getByRole('combobox'));
    fireEvent.click(within(screen.getByRole('listbox')).getByRole('option', { name: 'Deutsch' }));
    expect(onChange).toHaveBeenLastCalledWith('de');

    fireEvent.mouseDown(screen.getByRole('combobox'));
    fireEvent.click(
      within(screen.getByRole('listbox')).getByRole('option', {
        name: 'System default (English)',
      }),
    );
    expect(onChange).toHaveBeenLastCalledWith('');
  });
});
