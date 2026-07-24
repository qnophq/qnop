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
import type { DashboardResponse, DocumentSummary } from '../api/generated';
import { useDashboard } from '../api/hooks/useDashboard';
import { useReviews } from '../api/hooks/useReviews';
import { useUserProfile } from '../api/hooks/useUsers';
import { buildTheme } from '../theme/theme';
import { useAuthStore } from '../stores/authStore';
import { HomePage } from './HomePage';

vi.mock('../api/hooks/useReviews', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/hooks/useReviews')>()),
  useReviews: vi.fn(),
}));
vi.mock('../api/hooks/useDashboard', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/hooks/useDashboard')>()),
  useDashboard: vi.fn(),
}));
vi.mock('../api/hooks/useUsers', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/hooks/useUsers')>()),
  useUserProfile: vi.fn(),
}));

const ME = 'me';

function review(overrides: Partial<DocumentSummary> = {}): DocumentSummary {
  return {
    id: 'd1',
    title: 'Master agreement',
    ownerId: 'someone-else',
    workflowState: 'IN_REVIEW',
    latestVersionNumber: 1,
    annotationCount: 4,
    openAnnotationCount: 2,
    participants: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
    ...overrides,
  } as DocumentSummary;
}

function mockData(reviews: DocumentSummary[], dashboard?: Partial<DashboardResponse>) {
  vi.mocked(useReviews).mockReturnValue({
    isPending: false,
    isError: false,
    data: { items: reviews, total: reviews.length, page: 0, size: 100 },
  } as unknown as ReturnType<typeof useReviews>);
  vi.mocked(useDashboard).mockReturnValue({
    isPending: false,
    isError: false,
    data: {
      replies: [],
      activity: [],
      stats: { resolvedThisWeek: 0 },
      ...dashboard,
    },
  } as unknown as ReturnType<typeof useDashboard>);
  vi.mocked(useUserProfile).mockReturnValue({
    isPending: false,
    isError: false,
    data: {
      id: ME,
      slug: 'mia-member',
      displayName: 'Mia Member',
      stats: {
        reviewsOwned: 3,
        reviewsParticipating: 5,
        annotationsRaised: 12,
        annotationsResolved: 7,
        commentsWritten: 4,
      },
    },
  } as unknown as ReturnType<typeof useUserProfile>);
}

function renderPage() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/reviews/:documentId" element={<div data-testid="review-page" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  useAuthStore.setState({ userId: ME, displayName: 'Mia Member' });
});

describe('HomePage dashboard (issue #454)', () => {
  it('splits the two hats: waiting on you vs. my reviews', () => {
    mockData([
      review({ id: 'w1', title: 'Their contract' }),
      review({ id: 'o1', title: 'My own paper', ownerId: ME }),
    ]);
    renderPage();

    expect(screen.getAllByText('Waiting on you').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Their contract')).toBeInTheDocument();
    expect(screen.getByText('My own paper')).toBeInTheDocument();
  });

  it('greets by first name and shows the glance numbers', () => {
    mockData([review({ id: 'w1' }), review({ id: 'o1', ownerId: ME })], {
      stats: { resolvedThisWeek: 8 },
    });
    renderPage();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/Good .*, Mia\./);
    expect(screen.getByText('Resolved this week').previousSibling).toHaveTextContent('8');
    expect(screen.getByText('Waiting on you', { selector: 'p' })).toBeInTheDocument();
  });

  it('cues the owner when every annotation is settled', () => {
    mockData([review({ id: 'o1', ownerId: ME, annotationCount: 3, openAnnotationCount: 0 })]);
    renderPage();

    expect(screen.getByText('Ready to finalize')).toBeInTheDocument();
  });

  it('treats emptiness as a designed state in every box (#588)', () => {
    // One closed foreign review: the dashboard renders (not the brand-new
    // empty page), yet every box is empty.
    mockData([review({ workflowState: 'FINALIZED', ownerId: 'someone-else' })]);
    renderPage();

    expect(screen.getByText('All caught up!')).toBeInTheDocument();
    expect(screen.getByText('Nothing on the clock')).toBeInTheDocument();
    expect(screen.getByText('No reviews yet')).toBeInTheDocument();
    expect(screen.getByText('All quiet')).toBeInTheDocument();
    expect(screen.getByText('No replies yet')).toBeInTheDocument();
  });

  it('leads each review row with the typed document icon', () => {
    mockData([review({ id: 'w1', title: 'A pdf review', contentType: 'application/pdf' })]);
    renderPage();

    expect(screen.getByRole('img', { name: 'PDF document' })).toBeInTheDocument();
  });

  it('lists deadlines with the urgency label and links replies into the thread', () => {
    mockData([review({ id: 'd-due', title: 'Due thing', dueAt: '2020-01-01T00:00:00Z' })], {
      replies: [
        {
          commentId: 'c9',
          annotationId: 'a9',
          documentId: 'd-due',
          documentTitle: 'Due thing',
          authorDisplayName: 'Anna Krause',
          body: 'I disagree with the wording.',
          annotationExcerpt: 'please clarify',
          createdAt: '2026-07-11T09:00:00Z',
        },
      ],
    });
    renderPage();

    expect(screen.getByText(/Overdue by/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('I disagree with the wording.'));
    expect(screen.getByTestId('review-page')).toBeInTheDocument();
  });

  it('tells the activity story in sentences', () => {
    mockData([review()], {
      activity: [
        {
          type: 'annotation.resolved',
          documentId: 'd1',
          documentTitle: 'Master agreement',
          actorDisplayName: 'Ben Roth',
          createdAt: '2026-07-11T08:00:00Z',
        },
      ],
    });
    renderPage();

    expect(screen.getByText('Ben Roth')).toBeInTheDocument();
    expect(screen.getByText(/resolved an annotation in/)).toBeInTheDocument();
  });

  it('offers the continue strip once a review was visited', () => {
    localStorage.setItem(
      'qnop-recent-reviews',
      JSON.stringify([{ id: 'd1', slug: 'master-agreement', title: 'Master agreement' }]),
    );
    mockData([review()]);
    renderPage();

    fireEvent.click(
      screen
        .getByText('Continue where you left off:')
        .parentElement!.querySelector('.MuiChip-root')!,
    );
    expect(screen.getByTestId('review-page')).toBeInTheDocument();
  });

  it('welcomes a brand-new user with the empty state', () => {
    mockData([]);
    renderPage();

    expect(screen.getByTestId('empty-dashboard')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Start your first review/ })).toBeInTheDocument();
    expect(screen.getByText('Three moves to your first review')).toBeInTheDocument();
  });

  it("shows the caller's reviewer card with the profile stats", () => {
    mockData([review()]);
    renderPage();

    expect(screen.getByText('Your reviewer card')).toBeInTheDocument();
    // The four contribution numbers from the public-profile aggregates.
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('Raised')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View profile' })).toBeInTheDocument();
    // Earned achievements render as unlocked stickers.
    expect(screen.getByLabelText(/Liftoff: Started a review/)).toBeInTheDocument();
  });
});
