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

import { act, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { AllCaughtUpState, MessagesEmptyState } from './MessagesEmptyState';

function renderWithProviders(ui: ReactNode) {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MemoryRouter>{ui}</MemoryRouter>
    </ThemeProvider>,
  );
}

/** The inbox's empty states in German (issue #836): every visible string comes from the catalog. */
describe('MessagesEmptyState in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the cold-start state, its perks and the illustration label in German', () => {
    renderWithProviders(<MessagesEmptyState />);

    expect(
      screen.getByRole('heading', { name: 'Dein Postfach ist auf Empfang' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Erwähnungen, die dich nennen')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Zu deinen Reviews/ })).toBeInTheDocument();
    expect(
      screen.getByRole('img', {
        name: 'Ein leeres Postfach, das auf seine erste Benachrichtigung wartet',
      }),
    ).toBeInTheDocument();
  });

  it('follows a language switch while shown', async () => {
    renderWithProviders(<AllCaughtUpState />);
    expect(
      screen.getByText(
        'Du hast alles gelesen. Neue Aktivität in deinen Reviews erscheint hier, sobald sie passiert.',
      ),
    ).toBeInTheDocument();

    await act(() => applyLocale('en'));

    expect(screen.getByRole('heading', { name: 'Inbox zero' })).toBeInTheDocument();
  });
});
