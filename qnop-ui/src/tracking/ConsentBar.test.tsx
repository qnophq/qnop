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

import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { applyLocale } from '../i18n';
import { renderWithProviders } from '../test/renderWithProviders';
import { ConsentBar } from './ConsentBar';

/** The usage-measurement question in both languages (issue #836). */
describe('ConsentBar', () => {
  afterEach(async () => {
    await act(() => applyLocale('en'));
  });

  it('asks in English and reports the decision', () => {
    const onDecide = vi.fn();
    renderWithProviders(<ConsentBar onDecide={onDecide} />);

    expect(screen.getByRole('dialog', { name: 'Usage measurement' })).toBeInTheDocument();
    expect(screen.getByText(/May we count which pages get used\?/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'No thanks' }));
    expect(onDecide).toHaveBeenCalledWith('denied');
  });

  it('asks in German', async () => {
    await act(() => applyLocale('de'));
    const onDecide = vi.fn();
    renderWithProviders(<ConsentBar onDecide={onDecide} />);

    expect(screen.getByRole('dialog', { name: 'Nutzungsmessung' })).toBeInTheDocument();
    expect(
      screen.getByText(/Dürfen wir zählen, welche Seiten genutzt werden\?/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Erlauben' }));
    expect(onDecide).toHaveBeenCalledWith('granted');
  });
});
