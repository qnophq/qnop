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

/** Portuguese (Brazilian), the fifth UI language (issue #854): wiring beyond catalog parity. */
describe('Portuguese', () => {
  beforeEach(async () => {
    await act(() => applyLocale('pt'));
  });
  afterEach(async () => {
    await act(() => applyLocale('en'));
  });

  it('switches the document language and renders a page in Portuguese', () => {
    expect(document.documentElement.lang).toBe('pt');
    renderWithProviders(<NotFoundPage />);
    expect(screen.getByRole('link', { name: /painel/i })).toBeInTheDocument();
  });

  it('formats with the pt-BR conventions', () => {
    expect(intlLocale('pt')).toBe('pt-BR');
    expect(formatDateTime('2026-10-13T12:00:00Z', 'UTC', 'pt')).toMatch(/13.*out/i);
  });

  it('treats zero and one as singular, as Portuguese does, and covers millions', () => {
    // CLDR Portuguese: one (0 and 1), many (e.g. 1 000 000), other. The count is interpolated
    // unformatted in every language; this asserts the plural form, not number formatting.
    expect(i18n.t('time.due.inDays', { count: 0 })).toMatch(/\b0 dia\b/);
    expect(i18n.t('time.due.inDays', { count: 1 })).toMatch(/\b1 dia\b/);
    expect(i18n.t('time.due.inDays', { count: 3 })).toMatch(/\b3 dias\b/);
    expect(i18n.t('time.due.inDays', { count: 1_000_000 })).toMatch(/1000000 dias/);
  });
});
