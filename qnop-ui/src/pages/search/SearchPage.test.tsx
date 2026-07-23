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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { buildTheme } from '../../theme/theme';
import { SearchPage } from './SearchPage';

const quick = {
  data: {
    reviews: { items: [], total: 4 },
    annotations: { items: [], total: 3 },
    comments: { items: [], total: 5 },
    users: { items: [], total: 2 },
    teams: { items: [], total: 1 },
  },
  isPending: false,
  isError: false,
};
const discussions = {
  data: {
    items: [
      {
        commentId: 'c1',
        annotationId: 'a1',
        documentId: 'd1',
        documentSlug: 'q3-report',
        documentTitle: 'Q3 report',
        annotationStatus: 'OPEN',
        excerpt: '…the liability clause…',
      },
    ],
    total: 3,
    page: 0,
    size: 20,
  },
  isPending: false,
  isError: false,
};
const reviews = {
  data: {
    items: [{ id: 'r1', slug: 'q3-report', title: 'Q3 report', workflowState: 'IN_REVIEW' }],
    total: 4,
    page: 0,
    size: 20,
  },
  isPending: false,
  isError: false,
};
const users = {
  data: {
    items: [{ userId: 'u1', displayName: 'Mia Member', slug: 'mia-member' }],
    total: 2,
    page: 0,
    size: 20,
  },
  isPending: false,
  isError: false,
};
const teams = {
  data: {
    items: [
      { teamId: 't1', name: 'Alpha', slug: 'alpha', viewable: true },
      { teamId: 't2', name: 'Alchemy', slug: 'alchemy', viewable: false },
    ],
    total: 2,
    page: 0,
    size: 20,
  },
  isPending: false,
  isError: false,
};

vi.mock('../../api/hooks/useSearch', () => ({
  SEARCH_MIN_LENGTH: 2,
  useSearchQuick: () => quick,
  useSearchReviews: () => reviews,
  useSearchAnnotations: () => discussions,
  useSearchComments: () => discussions,
  useSearchUsers: () => users,
  useSearchTeams: () => teams,
}));

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderPage(initialEntry = '/search?q=alpha') {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route
            path="/search"
            element={
              <>
                <SearchPage />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

describe('SearchPage', () => {
  beforeEach(() => {
    quick.isPending = false;
  });

  it('reads the query from the URL and shows counted type chips', () => {
    renderPage();

    expect(screen.getByLabelText('Search reviews, people and teams')).toHaveValue('alpha');
    expect(screen.getByText('Reviews (4)')).toBeInTheDocument();
    expect(screen.getByText('People (2)')).toBeInTheDocument();
    expect(screen.getByText('Teams (1)')).toBeInTheDocument();
    // Default type: reviews, milestone-tracked rows deep-linking to the review.
    const row = screen.getByTestId('search-row-review');
    expect(row).toHaveTextContent('Q3 report');
    expect(screen.getByRole('link', { name: 'Q3 report' })).toHaveAttribute(
      'href',
      '/reviews/q3-report',
    );
  });

  it('lists discussion hits with their thread deep link under the annotation/comment types', () => {
    renderPage('/search?q=liability&type=comments');

    expect(screen.getByText('Annotations (3)')).toBeInTheDocument();
    expect(screen.getByText('Comments (5)')).toBeInTheDocument();
    const row = screen.getByTestId('search-row-comment');
    expect(row).toHaveTextContent('in Q3 report');
    expect(screen.getByRole('link', { name: '…the liability clause…' })).toHaveAttribute(
      'href',
      '/reviews/q3-report?annotation=a1&comment=c1',
    );
  });

  it('switches the type through the URL', () => {
    renderPage();

    fireEvent.click(screen.getByText('People (2)'));

    expect(screen.getByTestId('location')).toHaveTextContent('type=users');
    expect(screen.getByTestId('search-row-user')).toHaveTextContent('Mia Member');
  });

  it('honours the type from the URL and locks unreachable teams', () => {
    renderPage('/search?q=alpha&type=teams');

    const rows = screen.getAllByTestId('search-row-team');
    expect(rows[0]).toHaveTextContent('Alpha');
    expect(screen.getByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '/my-teams/alpha');
    expect(rows[1]).toHaveTextContent('Alchemy');
    expect(rows[1]).toHaveTextContent('Not a member');
    expect(screen.queryByRole('link', { name: 'Alchemy' })).toBeNull();
  });

  it('asks for more input below the minimum query length', () => {
    renderPage('/search?q=a');

    expect(screen.getByTestId('search-too-short')).toBeInTheDocument();
    expect(screen.queryByTestId('search-row-review')).toBeNull();
  });
});
