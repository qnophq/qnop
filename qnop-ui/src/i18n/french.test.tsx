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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import i18n, { applyLocale } from '.';
import { NotFoundPage } from '../pages/errors/NotFoundPage';
import { renderWithProviders } from '../test/renderWithProviders';
import { formatDateTime } from '../utils/formatDate';
import { intlLocale } from './locales';

/** French, the fourth UI language (issue #851): wiring beyond the catalog parity test. */
describe('French', () => {
  beforeEach(async () => {
    await act(() => applyLocale('fr'));
  });
  afterEach(async () => {
    await act(() => applyLocale('en'));
  });

  it('switches the document language and renders a page in French', () => {
    expect(document.documentElement.lang).toBe('fr');
    renderWithProviders(<NotFoundPage />);
    expect(screen.getByRole('link', { name: /tableau de bord/i })).toBeInTheDocument();
  });

  it('formats with the fr-FR conventions', () => {
    expect(intlLocale('fr')).toBe('fr-FR');
    expect(formatDateTime('2026-10-13T12:00:00Z', 'UTC', 'fr')).toMatch(/13 oct/i);
  });

  it('treats zero and one as singular, as French does, and covers millions', () => {
    // CLDR French: one (0 and 1), many (e.g. 1 000 000), other.
    expect(i18n.t('time.due.inDays', { count: 0 })).toMatch(/\b0 jour\b/);
    expect(i18n.t('time.due.inDays', { count: 1 })).toMatch(/\b1 jour\b/);
    expect(i18n.t('time.due.inDays', { count: 3 })).toMatch(/\b3 jours\b/);
    expect(i18n.t('time.due.inDays', { count: 1_000_000 })).toMatch(/1000000 jours/);
  });
});
