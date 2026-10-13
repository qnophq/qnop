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

/** Spanish, the third UI language (issue #849): wiring beyond the catalog parity test. */
describe('Spanish', () => {
  beforeEach(async () => {
    await act(() => applyLocale('es'));
  });
  afterEach(async () => {
    await act(() => applyLocale('en'));
  });

  it('switches the document language and renders a page in Spanish', () => {
    expect(document.documentElement.lang).toBe('es');
    renderWithProviders(<NotFoundPage />);
    expect(screen.getByRole('link', { name: /Volver al panel/ })).toBeInTheDocument();
  });

  it('formats with the es-ES conventions', () => {
    expect(intlLocale('es')).toBe('es-ES');
    expect(formatDateTime('2026-10-13T12:00:00Z', 'UTC', 'es')).toMatch(/13.*oct/i);
  });

  it('uses the singular for one and the plural for every other count, millions included', () => {
    // CLDR gives Spanish a "many" category (e.g. 1 000 000); the catalogs only define one/other,
    // and i18next falls back from many to other.
    expect(i18n.t('time.due.inDays', { count: 1 })).toMatch(/1 día/);
    expect(i18n.t('time.due.inDays', { count: 3 })).toMatch(/3 días/);
    expect(i18n.t('time.due.inDays', { count: 1_000_000 })).toMatch(/1000000 días/);
  });
});
