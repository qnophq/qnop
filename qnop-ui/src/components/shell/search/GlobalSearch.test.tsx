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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { GlobalSearchResponse } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { GlobalSearch } from './GlobalSearch';

const quickState: {
  data: GlobalSearchResponse | undefined;
  isPending: boolean;
  isError: boolean;
} = { data: undefined, isPending: false, isError: false };

vi.mock('../../../api/hooks/useSearch', () => ({
  SEARCH_MIN_LENGTH: 2,
  useSearchQuick: () => quickState,
}));

const RESPONSE: GlobalSearchResponse = {
  reviews: {
    items: [
      { id: 'r1', slug: 'q3-report', title: 'Q3 payment report', workflowState: 'IN_REVIEW' },
    ],
    total: 7,
  },
  annotations: {
    items: [
      {
        commentId: 'c1',
        annotationId: 'a1',
        documentId: 'd1',
        documentSlug: 'q3-report',
        documentTitle: 'Q3 payment report',
        annotationStatus: 'OPEN',
        excerpt: '…rework the payment schedule…',
      },
    ],
    total: 3,
  },
  comments: {
    items: [
      {
        commentId: 'c2',
        annotationId: 'a1',
        documentId: 'd1',
        documentSlug: 'q3-report',
        documentTitle: 'Q3 payment report',
        annotationStatus: 'RESOLVED',
        excerpt: '…the payment reply…',
      },
    ],
    total: 1,
  },
  users: {
    items: [{ userId: 'u1', displayName: 'Mia Member', slug: 'mia-member' }],
    total: 1,
  },
  teams: {
    items: [
      { teamId: 't1', name: 'Alpha', slug: 'alpha', viewable: true },
      { teamId: 't2', name: 'Alchemy', slug: 'alchemy', viewable: false },
    ],
    total: 2,
  },
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderSearch() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <GlobalSearch />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

const input = () => screen.getByLabelText('Search reviews, people and teams');

describe('GlobalSearch', () => {
  beforeEach(() => {
    quickState.data = RESPONSE;
    quickState.isPending = false;
    quickState.isError = false;
    useAuthStore.setState({ userId: 'viewer-1' });
  });

  it('opens the grouped dropdown with counted sections and milestone-tracked review hits', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'pay' } });

    expect(await screen.findByTestId('global-search-dropdown')).toBeInTheDocument();
    expect(screen.getByText('Reviews')).toBeInTheDocument();
    expect(screen.getByText('People')).toBeInTheDocument();
    expect(screen.getByText('Teams')).toBeInTheDocument();
    expect(screen.getByText('Q3 payment report')).toBeInTheDocument();
    // The discussion groups carry their matched excerpts and status cues.
    expect(screen.getByText('Annotations')).toBeInTheDocument();
    expect(screen.getByText('Comments')).toBeInTheDocument();
    expect(screen.getByText('…rework the payment schedule…')).toBeInTheDocument();
    expect(screen.getByText('…the payment reply…')).toBeInTheDocument();
    // The #568 state language at row scale.
    expect(screen.getByTestId('milestone-dots')).toHaveAccessibleName('In review');
    // The review group was capped (1 of 7) — its continuation is offered.
    expect(screen.getByText('See all 7 results')).toBeInTheDocument();
  });

  it('deep-links a review hit and closes on navigation', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'pay' } });

    fireEvent.click(await screen.findByTestId('search-hit-review'));

    expect(screen.getByTestId('location')).toHaveTextContent('/reviews/q3-report');
    await waitFor(() =>
      expect(screen.queryByTestId('global-search-dropdown')).not.toBeInTheDocument(),
    );
  });

  it('deep-links discussion hits into the thread — replies with their comment anchor', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'pay' } });

    fireEvent.click(await screen.findByTestId('search-hit-annotation'));
    expect(screen.getByTestId('location')).toHaveTextContent('/reviews/q3-report?annotation=a1');

    fireEvent.change(input(), { target: { value: 'pay' } });
    fireEvent.click(await screen.findByTestId('search-hit-comment'));
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/reviews/q3-report?annotation=a1&comment=c2',
    );
  });

  it('locks a team hit the caller may not open, links the reachable one', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'al' } });

    expect(await screen.findByTestId('search-hit-team')).toHaveTextContent('Alpha');
    expect(screen.getByTestId('search-hit-team-locked')).toHaveTextContent('Alchemy');

    fireEvent.click(screen.getByTestId('search-hit-team'));
    expect(screen.getByTestId('location')).toHaveTextContent('/my-teams/alpha');
  });

  it('routes "see all" onto the results page with query and type', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'pay' } });

    fireEvent.click(await screen.findByText('See all 7 results'));

    expect(screen.getByTestId('location')).toHaveTextContent('/search?q=pay&type=reviews');
  });

  it('hints below the minimum length and shows the empty state on no matches', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'p' } });
    expect(await screen.findByText(/keep typing/i)).toBeInTheDocument();

    quickState.data = {
      reviews: { items: [], total: 0 },
      annotations: { items: [], total: 0 },
      comments: { items: [], total: 0 },
      users: { items: [], total: 0 },
      teams: { items: [], total: 0 },
    };
    fireEvent.change(input(), { target: { value: 'zz' } });
    expect(await screen.findByTestId('global-search-empty')).toBeInTheDocument();
  });

  it('closes on Escape', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'pay' } });
    expect(await screen.findByTestId('global-search-dropdown')).toBeInTheDocument();

    fireEvent.keyDown(input(), { key: 'Escape' });
    await waitFor(() =>
      expect(screen.queryByTestId('global-search-dropdown')).not.toBeInTheDocument(),
    );
  });

  it('submits to the results page on Enter', async () => {
    renderSearch();
    fireEvent.change(input(), { target: { value: 'payment terms' } });
    fireEvent.keyDown(input(), { key: 'Enter' });

    expect(screen.getByTestId('location')).toHaveTextContent('/search?q=payment%20terms');
  });

  it('pulls focus on Cmd+K and Ctrl+K from anywhere', () => {
    renderSearch();

    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    expect(input()).toHaveFocus();

    (input() as HTMLElement).blur();
    fireEvent.keyDown(window, { key: 'K', ctrlKey: true });
    expect(input()).toHaveFocus();
  });

  it('advertises the shortcut while resting and grows on focus (Docker Hub pattern)', () => {
    renderSearch();
    const pill = () => screen.getByTestId('search-shortcut-hint').closest('[data-expanded]');

    expect(screen.getByTestId('search-shortcut-hint')).toBeInTheDocument();
    expect(pill()).toHaveAttribute('data-expanded', 'false');

    fireEvent.focus(input());
    const expanded = document.querySelector('[data-expanded]');
    expect(expanded).toHaveAttribute('data-expanded', 'true');
    // The kbd hint yields to the caret.
    expect(screen.queryByTestId('search-shortcut-hint')).toBeNull();

    fireEvent.blur(input());
    expect(document.querySelector('[data-expanded]')).toHaveAttribute('data-expanded', 'false');
  });
});
