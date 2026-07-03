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
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import type { DocumentSummary } from '../../api/generated';
import { ParticipantKind } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { useReviews } from '../../api/hooks/useReviews';
import { ReviewsPage } from './ReviewsPage';

vi.mock('../../api/hooks/useReviews', () => ({
  useReviews: vi.fn(),
}));

const ME = '00000000-0000-0000-0000-0000000000aa';
const OTHER = '00000000-0000-0000-0000-0000000000bb';

function summary(overrides: Partial<DocumentSummary>): DocumentSummary {
  return {
    id: 'doc-x',
    title: 'Untitled',
    ownerId: ME,
    workflowState: 'DRAFT',
    latestVersionNumber: 1,
    annotationCount: 0,
    openAnnotationCount: 0,
    participants: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
    ...overrides,
  };
}

const REVIEWS: DocumentSummary[] = [
  summary({
    id: 'doc-1',
    title: 'NDA Acme Corp',
    workflowState: 'IN_REVIEW',
    annotationCount: 3,
    openAnnotationCount: 1,
    participants: [
      { id: 'p1', kind: ParticipantKind.User, principalId: OTHER, displayName: 'Max Member' },
    ],
    updatedAt: '2026-07-02T12:00:00Z',
  }),
  summary({
    id: 'doc-2',
    title: 'Architecture handbook',
    ownerId: OTHER,
    workflowState: 'DRAFT',
    updatedAt: '2026-07-01T09:00:00Z',
  }),
  summary({
    id: 'doc-3',
    title: 'Final contract',
    ownerId: OTHER,
    workflowState: 'FINALIZED',
    annotationCount: 2,
    openAnnotationCount: 0,
    updatedAt: '2026-06-20T08:00:00Z',
  }),
];

type Queryish = { data?: unknown; isPending?: boolean; isError?: boolean; refetch?: () => void };
function mockReviews(value: Queryish) {
  vi.mocked(useReviews).mockReturnValue({
    isPending: false,
    isError: false,
    refetch: vi.fn(),
    ...value,
  } as unknown as ReturnType<typeof useReviews>);
}

function renderPage() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={['/reviews']}>
        <Routes>
          <Route path="/reviews" element={<ReviewsPage />} />
          <Route path="/reviews/new" element={<div data-testid="new-review-probe" />} />
          <Route path="/reviews/:documentId" element={<div data-testid="detail-probe" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  useAuthStore.setState({ userId: ME, isAuthenticated: true });
  mockReviews({ data: { items: REVIEWS, total: REVIEWS.length, page: 0, size: 100 } });
});

describe('ReviewsPage', () => {
  it('lists all reviews with role and workflow badges', () => {
    renderPage();

    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('Architecture handbook')).toBeInTheDocument();
    expect(screen.getByText('Final contract')).toBeInTheDocument();
    expect(screen.getByText('Owner')).toBeInTheDocument();
    expect(screen.getAllByText('Reviewer')).toHaveLength(2);
    expect(screen.getByText('In review')).toBeInTheDocument();
    expect(screen.getByText('Finalized')).toBeInTheDocument();
  });

  it('shows decided/total progress for reviews with annotations', () => {
    renderPage();

    expect(
      screen.getByRole('progressbar', { name: '2 of 3 annotations decided' }),
    ).toBeInTheDocument();
    expect(screen.getByText('2/3')).toBeInTheDocument();
  });

  it('filters by title search', () => {
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('Search reviews…'), {
      target: { value: 'nda' },
    });

    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.queryByText('Architecture handbook')).not.toBeInTheDocument();
  });

  it('filters by role via the chip and shows counts', () => {
    renderPage();

    fireEvent.click(screen.getByText('Owned by me (1)'));

    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.queryByText('Architecture handbook')).not.toBeInTheDocument();
    expect(screen.queryByText('Final contract')).not.toBeInTheDocument();
  });

  it('filters by workflow status and toggles the chip off again', () => {
    renderPage();

    fireEvent.click(screen.getByText('Closed (1)'));
    expect(screen.getByText('Final contract')).toBeInTheDocument();
    expect(screen.queryByText('NDA Acme Corp')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Closed (1)'));
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
  });

  it('offers to clear filters when nothing matches', () => {
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('Search reviews…'), {
      target: { value: 'does-not-exist' },
    });
    expect(screen.getByText('No reviews match your filters.')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Clear filters'));
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
  });

  it('navigates to the review on row click', () => {
    renderPage();

    fireEvent.click(screen.getByTestId('review-row-doc-1'));

    expect(screen.getByTestId('detail-probe')).toBeInTheDocument();
  });

  it('navigates to the wizard from the header CTA', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /New review/ }));

    expect(screen.getByTestId('new-review-probe')).toBeInTheDocument();
  });

  it('switches to cards and persists the choice', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Card view' }));

    expect(screen.getByTestId('review-card-doc-1')).toBeInTheDocument();
    expect(screen.queryByTestId('review-row-doc-1')).not.toBeInTheDocument();
    expect(localStorage.getItem('qnop-reviews-view')).toBe('cards');
  });

  it('restores the persisted card view', () => {
    localStorage.setItem('qnop-reviews-view', 'cards');
    renderPage();

    expect(screen.getByTestId('review-card-doc-1')).toBeInTheDocument();
  });

  it('shows the hero empty state without any reviews', () => {
    mockReviews({ data: { items: [], total: 0, page: 0, size: 100 } });
    renderPage();

    expect(screen.getByText('No reviews yet')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /New review/ })).not.toHaveLength(0);
  });

  it('shows a loading skeleton while pending', () => {
    mockReviews({ isPending: true });
    renderPage();

    expect(screen.getByTestId('reviews-loading')).toBeInTheDocument();
  });

  it('shows an error with retry', () => {
    const refetch = vi.fn();
    mockReviews({ isError: true, refetch });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('flags an overdue open review and shows an upcoming deadline plainly', () => {
    const DAY_MS = 24 * 60 * 60_000;
    mockReviews({
      data: {
        items: [
          summary({
            id: 'doc-overdue',
            title: 'Overdue review',
            workflowState: 'IN_REVIEW',
            dueAt: new Date(Date.now() - 2 * DAY_MS - 60_000).toISOString(),
          }),
          summary({
            id: 'doc-upcoming',
            title: 'Upcoming review',
            workflowState: 'IN_REVIEW',
            dueAt: new Date(Date.now() + 3 * DAY_MS + 60_000).toISOString(),
          }),
        ],
        total: 2,
        page: 0,
        size: 100,
      },
    });
    renderPage();

    const overdue = screen.getByText('overdue by 2 days');
    expect(overdue).toBeInTheDocument();
    expect(overdue).toHaveAttribute('data-overdue', 'true');
    expect(screen.getByText('due in 3 days')).toBeInTheDocument();
  });

  it('does not flag a passed deadline on a closed review', () => {
    const DAY_MS = 24 * 60 * 60_000;
    mockReviews({
      data: {
        items: [
          summary({
            id: 'doc-closed',
            title: 'Closed review',
            workflowState: 'FINALIZED',
            dueAt: new Date(Date.now() - 5 * DAY_MS).toISOString(),
          }),
        ],
        total: 1,
        page: 0,
        size: 100,
      },
    });
    renderPage();

    expect(screen.getByText('overdue by 5 days')).not.toHaveAttribute('data-overdue', 'true');
  });

  it('offers a due-date sort option', () => {
    renderPage();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Sort' }));
    expect(screen.getByRole('option', { name: 'Due date' })).toBeInTheDocument();
  });
});
