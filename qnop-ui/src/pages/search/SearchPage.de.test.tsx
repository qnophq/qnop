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
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router';
import { applyLocale } from '../../i18n';
import { buildTheme } from '../../theme/theme';
import { SearchPage } from './SearchPage';

const empty = {
  data: { items: [], total: 0, page: 0, size: 20 },
  isPending: false,
  isError: false,
};
const quick = {
  data: {
    reviews: { items: [], total: 4 },
    annotations: { items: [], total: 0 },
    comments: { items: [], total: 0 },
    users: { items: [], total: 0 },
    teams: { items: [], total: 0 },
  },
  isPending: false,
  isError: false,
};

vi.mock('../../api/hooks/useSearch', () => ({
  SEARCH_MIN_LENGTH: 2,
  useSearchQuick: () => quick,
  useSearchReviews: () => empty,
  useSearchAnnotations: () => empty,
  useSearchComments: () => empty,
  useSearchUsers: () => empty,
  useSearchTeams: () => empty,
}));

function renderPage(entry: string) {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MemoryRouter initialEntries={[entry]}>
        <SearchPage />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

/** The search page in German (issue #836): the plural hint, groups and the quoted query. */
describe('SearchPage in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('asks for more characters with the plural hint', () => {
    renderPage('/search?q=a');

    expect(screen.getByRole('heading', { name: 'Suche' })).toBeInTheDocument();
    expect(screen.getByTestId('search-too-short')).toHaveTextContent(
      'Gib mindestens 2 Zeichen ein, um zu suchen.',
    );
  });

  it('labels the score cards and quotes the query German-style', () => {
    renderPage('/search?q=vertrag');

    expect(screen.getByRole('button', { name: 'Personen (0)' })).toBeInTheDocument();
    expect(screen.getByText('Ergebnisse für „vertrag“')).toBeInTheDocument();
    expect(screen.getByText('Keine Treffer in dieser Gruppe.')).toBeInTheDocument();
  });
});
