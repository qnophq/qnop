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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { BoundaryFallback } from '../../components/errors/BoundaryFallback';
import { NotFoundPage } from './NotFoundPage';

function renderWithProviders(ui: ReactNode) {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MemoryRouter>{ui}</MemoryRouter>
    </ThemeProvider>,
  );
}

/** The error surfaces in German (issue #836): page copy and the actions passed as object props. */
describe('error pages in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the 404 page with German copy and actions', () => {
    renderWithProviders(<NotFoundPage />);

    expect(
      screen.getByRole('heading', { name: 'Diese Seite hat sich zum Papierflieger gefaltet' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Zurück zum Dashboard/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Meine Reviews/ })).toBeInTheDocument();
  });

  it('falls back to the German default title in the boundary fallback', () => {
    renderWithProviders(<BoundaryFallback onRetry={() => undefined} />);

    expect(screen.getByText('Etwas ist schiefgelaufen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Erneut versuchen/ })).toBeInTheDocument();
  });
});
