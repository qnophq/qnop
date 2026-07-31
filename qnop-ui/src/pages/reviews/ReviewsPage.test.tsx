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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
    ownerDisplayName: 'Me Myself',
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
    ownerDisplayName: 'Someone Else',
    workflowState: 'DRAFT',
    updatedAt: '2026-07-01T09:00:00Z',
  }),
  summary({
    id: 'doc-3',
    title: 'Final contract',
    ownerId: OTHER,
    ownerDisplayName: 'Someone Else',
    workflowState: 'FINALIZED',
    annotationCount: 2,
    openAnnotationCount: 0,
    updatedAt: '2026-06-20T08:00:00Z',
  }),
  summary({
    id: 'doc-4',
    title: 'Old supplier terms',
    ownerId: OTHER,
    ownerDisplayName: 'Someone Else',
    workflowState: 'FINALIZED',
    archivedAt: '2026-04-01T08:00:00Z',
    updatedAt: '2026-03-20T08:00:00Z',
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

function renderPage(entry = '/reviews') {
  // The page lives inside one in the app; the dialogs it opens use the cache to
  // invalidate the lists after a deletion (issue #421).
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path="/reviews" element={<ReviewsPage />} />
            <Route path="/reviews/new" element={<div data-testid="new-review-probe" />} />
            <Route path="/reviews/:documentId" element={<div data-testid="detail-probe" />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
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
    expect(screen.getAllByText('Owner').length).toBeGreaterThanOrEqual(1);
    // Two foreign-owned rows are live work: the draft and the closed one — the
    // archived record is not among them by default (issue #578).
    expect(screen.getAllByText('Reviewer')).toHaveLength(2);
    expect(screen.getByText('In review')).toBeInTheDocument();
    expect(screen.getAllByText('Finalized').length).toBeGreaterThanOrEqual(1);
  });

  it('shows resolved/total progress for reviews with annotations', () => {
    renderPage();

    expect(
      screen.getByRole('progressbar', { name: '2 of 3 annotations resolved' }),
    ).toBeInTheDocument();
    expect(screen.getByText('2/3')).toBeInTheDocument();
  });

  it('filters by title search', () => {
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('Search by title or owner…'), {
      target: { value: 'nda' },
    });

    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.queryByText('Architecture handbook')).not.toBeInTheDocument();
  });

  // Issue #578: archived reviews are cold records — invisible until asked for.
  it('hides archived reviews by default and reveals them only via Archived (#578)', () => {
    renderPage();

    // ONE scope=all fetch backs every facet — no refetch on chip clicks. The
    // archived record IS loaded; the default facet just does not show it.
    expect(vi.mocked(useReviews)).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'all' }),
    );
    expect(screen.queryByText('Old supplier terms')).not.toBeInTheDocument();
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();

    // The counters agree: the default 'Active' facet stops at the archive
    // line, while 'Every state' counts the record in.
    expect(screen.getByText('Active (3)')).toBeInTheDocument();
    expect(screen.getByText('Every state (4)')).toBeInTheDocument();
    expect(screen.getByText('Archived (1)')).toBeInTheDocument();

    // The explicit opt-in brings the records in — and only the records, each
    // badged as a record rather than as live work (#576's neutral tone).
    fireEvent.click(screen.getByText('Archived (1)'));
    expect(screen.getByText('Old supplier terms')).toBeInTheDocument();
    expect(screen.queryByText('NDA Acme Corp')).not.toBeInTheDocument();
    expect(screen.getByText('Archived · Finalized')).toBeInTheDocument();

    // Toggling it off hides them again.
    fireEvent.click(screen.getByText('Archived (1)'));
    expect(screen.queryByText('Old supplier terms')).not.toBeInTheDocument();
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
  });

  it('spans every state at once — archive included — via Every state (#578)', () => {
    renderPage();

    // The widest lens is an explicit click, never the default: live work and
    // the archived record side by side.
    fireEvent.click(screen.getByText('Every state (4)'));
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('Final contract')).toBeInTheDocument();
    expect(screen.getByText('Old supplier terms')).toBeInTheDocument();

    // Deselecting falls back to the default, archived hidden again.
    fireEvent.click(screen.getByText('Every state (4)'));
    expect(screen.queryByText('Old supplier terms')).not.toBeInTheDocument();
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
  });

  it('keeps archived out of the Open and Closed slices too (#578)', () => {
    renderPage();

    fireEvent.click(screen.getByText('Closed (1)'));
    expect(screen.getByText('Final contract')).toBeInTheDocument();
    expect(screen.queryByText('Old supplier terms')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Open (2)'));
    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.queryByText('Old supplier terms')).not.toBeInTheDocument();
  });

  it('round-trips the archived facet through the URL (#578)', () => {
    // A shared link carrying the facet opens on the records…
    renderPage('/reviews?status=archived');
    expect(screen.getByText('Old supplier terms')).toBeInTheDocument();
    expect(screen.queryByText('NDA Acme Corp')).not.toBeInTheDocument();
  });

  it('treats a URL without the facet — and an unknown value — as hidden (#578)', () => {
    // …while the bare URL, and any value outside the facet set, mean the
    // default: no param can ever surface a record implicitly.
    renderPage('/reviews?status=bogus');

    expect(screen.queryByText('Old supplier terms')).not.toBeInTheDocument();
    expect(screen.getByText('Active (3)')).toBeInTheDocument();
  });

  it('explains a facet chip on hover', async () => {
    renderPage();

    fireEvent.mouseOver(screen.getByText('Every state (4)'));
    expect(
      await screen.findByRole('tooltip', {
        name: 'Everything at once — every workflow state, archived records included',
      }),
    ).toBeInTheDocument();
  });

  it('names the archive instead of blaming filters when only records are left (#578)', () => {
    mockReviews({
      data: { items: [REVIEWS[3]], total: 1, page: 0, size: 100 },
    });
    renderPage();

    // No filter is set, so "nothing matches your filters" would be a dead end.
    expect(
      screen.getByText('No active reviews — everything here has been archived.'),
    ).toBeVisible();
    expect(screen.queryByText('Clear filters')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show archived (1)' }));
    expect(screen.getByText('Old supplier terms')).toBeInTheDocument();
  });

  it('searches by owner name and filters via the advanced menu (#576 follow-up)', () => {
    renderPage();

    // Owner-name search: every review owned by "Someone Else" (fixture OTHER).
    fireEvent.change(screen.getByPlaceholderText('Search by title or owner…'), {
      target: { value: 'else' },
    });
    expect(screen.queryByText('NDA Acme Corp')).not.toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText('Search by title or owner…'), {
      target: { value: '' },
    });

    // The advanced menu: workflow-state facet narrows to Draft.
    fireEvent.click(screen.getByLabelText('Filter reviews'));
    fireEvent.click(within(screen.getByRole('menu')).getByText('Draft'));
    expect(screen.getByText('Architecture handbook')).toBeInTheDocument();
    expect(screen.queryByText('NDA Acme Corp')).not.toBeInTheDocument();
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

    fireEvent.change(screen.getByPlaceholderText('Search by title or owner…'), {
      target: { value: 'does-not-exist' },
    });
    expect(screen.getByText('No reviews match your filters.')).toBeInTheDocument();

    // Both the chip-row affordance and the empty-state button offer the reset.
    fireEvent.click(screen.getAllByText('Clear filters')[0]);
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

  it('shows the invitational hero empty state without any reviews', () => {
    mockReviews({ data: { items: [], total: 0, page: 0, size: 100 } });
    renderPage();

    expect(screen.getByRole('heading', { name: 'Start your first review' })).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: 'A fresh document waiting for its first review' }),
    ).toBeInTheDocument();
    // Both the header and the empty-state hero offer a way to start.
    expect(screen.getAllByRole('button', { name: /New review/ })).not.toHaveLength(0);
  });

  it('starts a new review from the empty-state action', () => {
    mockReviews({ data: { items: [], total: 0, page: 0, size: 100 } });
    renderPage();

    // The hero's own CTA (not the header's) navigates to the wizard.
    const heroButton = screen
      .getByRole('heading', { name: 'Start your first review' })
      .closest('.MuiPaper-root')!
      .querySelector('button')!;
    fireEvent.click(heroButton);
    expect(screen.getByTestId('new-review-probe')).toBeInTheDocument();
  });

  it('shows a loading skeleton while pending', () => {
    mockReviews({ isPending: true });
    renderPage();

    expect(screen.getByTestId('reviews-loading')).toBeInTheDocument();
  });

  it('shows the branded failure shell with a working retry (#611)', () => {
    const refetch = vi.fn();
    mockReviews({ isError: true, refetch });
    renderPage();

    // The load failure speaks the error pages' voice - ours, with a way onward.
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/didn't make it to the desk/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
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

describe('ReviewsPage — the admin moderation listing (#563)', () => {
  const foreign = summary({
    id: 'doc-9',
    title: 'A review nobody invited me to',
    ownerId: OTHER,
    ownerDisplayName: 'Someone Else',
    workflowState: 'IN_REVIEW',
    participating: false,
  });

  it('does not offer the switch to a member', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'MEMBER' });

    renderPage();

    // The API refuses them anyway; not showing the control keeps the UI from
    // promising something the server will deny.
    expect(screen.queryByTestId('participation-all')).not.toBeInTheDocument();
  });

  it('offers the switch to an admin', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });

    renderPage();

    expect(screen.getByTestId('participation-all')).toBeInTheDocument();
  });

  it('still offers the switch to an admin who owns no reviews at all', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [], total: 0, page: 0, size: 100 } });

    renderPage();

    // The switch lives in the filter row, which an empty list does not render —
    // without this an admin who only administers could never reach the workspace.
    expect(screen.getByTestId('participation-all')).toBeInTheDocument();
  });

  it("lists the caller's own reviews until the admin asks for all of them", () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    renderPage();

    // Default off, so an admin's own work is not drowned out on every visit.
    expect(vi.mocked(useReviews).mock.calls[0][0].participation).toBeUndefined();

    fireEvent.click(screen.getByTestId('participation-all'));

    const last = vi.mocked(useReviews).mock.calls.at(-1)![0];
    expect(last.participation).toBe('all');
    // Paged on the server, not sliced from one big fetch (issue #563): a
    // moderation view that stopped at the first hundred would read as "that is
    // everything".
    expect(last.size).toBe(20);
  });

  it('marks the rows the admin has no part in', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({
      data: { items: [foreign], total: 1, page: 0, size: 20, facets: { ...FACETS, roleAny: 1 } },
    });

    renderPage('/reviews?participation=all');

    expect(screen.getByText('A review nobody invited me to')).toBeInTheDocument();
    expect(screen.getByText('Not participating')).toBeInTheDocument();
    // Not "Reviewer": the admin is not on the roster, and saying so would be a
    // small lie in exactly the view where the context matters.
    expect(screen.queryByText('Reviewer')).not.toBeInTheDocument();
  });

  const FACETS = {
    owners: [{ id: OTHER, displayName: 'Someone Else' }],
    totalUnfiltered: 57,
    roleAny: 57,
    roleOwner: 2,
    roleReviewer: 5,
    roleObserver: 50,
    stateActive: 40,
    stateOpen: 31,
    stateClosed: 9,
    stateArchived: 17,
    stateAll: 57,
  };

  it('counts the chips from the whole workspace, not from the page on screen', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [foreign], total: 57, page: 0, size: 20, facets: FACETS } });

    renderPage('/reviews?participation=all');

    // One row is on screen and the numbers describe 57 reviews, which is the
    // whole point: a count taken from the page would describe the page while
    // appearing to describe the workspace.
    expect(screen.getByText('Owned by me (2)')).toBeInTheDocument();
    expect(screen.getByText('Reviewing (5)')).toBeInTheDocument();
    expect(screen.getByText('Every state (57)')).toBeInTheDocument();
    expect(screen.getByText('Archived (17)')).toBeInTheDocument();
  });

  it('offers the not-participating facet, and only while moderating', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [foreign], total: 57, page: 0, size: 20, facets: FACETS } });

    renderPage('/reviews?participation=all');

    expect(screen.getByText('Not participating (50)')).toBeInTheDocument();
  });

  it("has no not-participating facet in the caller's own listing", () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });

    renderPage();

    // There, every row is the caller's by construction — a facet that can never
    // match is a control that only puzzles.
    expect(screen.queryByText(/Not participating/)).not.toBeInTheDocument();
  });

  it('sends a chosen chip to the server rather than filtering the page', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [foreign], total: 57, page: 0, size: 20, facets: FACETS } });

    renderPage('/reviews?participation=all');
    fireEvent.click(screen.getByText('Not participating (50)'));

    const last = vi.mocked(useReviews).mock.calls.at(-1)![0];
    expect(last.role).toBe('observer');
    expect(last.participation).toBe('all');
  });

  it('splits the status chips into the retention and workflow slices', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [foreign], total: 57, page: 0, size: 20, facets: FACETS } });

    renderPage('/reviews?participation=all');
    fireEvent.click(screen.getByText('Closed (9)'));

    // 'Closed' means "not archived, and the workflow closed it" — two orthogonal
    // parameters on the wire, the same rule the browser-side facet follows.
    const closed = vi.mocked(useReviews).mock.calls.at(-1)![0];
    expect(closed.scope).toBe('active');
    expect(closed.lifecycle).toBe('closed');

    fireEvent.click(screen.getByText('Archived (17)'));
    const archived = vi.mocked(useReviews).mock.calls.at(-1)![0];
    expect(archived.scope).toBe('archived');
    expect(archived.lifecycle).toBe('any');
  });

  it('says a filter matched nothing instead of greeting a full workspace as empty', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    // A filter combination with no hits, in a workspace holding 57 reviews.
    mockReviews({
      data: {
        items: [],
        total: 0,
        page: 0,
        size: 20,
        // Nothing archived either, so the plain "no matches" line is the one
        // under test rather than the archive-only variant (#578).
        facets: { ...FACETS, roleAny: 0, stateActive: 0, stateArchived: 0, totalUnfiltered: 57 },
      },
    });

    renderPage('/reviews?participation=all&role=observer');

    expect(screen.getByText('No reviews match your filters.')).toBeInTheDocument();
    expect(screen.queryByText(/Start your first review/i)).not.toBeInTheDocument();
    // And the chips stay: without them there is no way to undo the filter that
    // emptied the page.
    expect(screen.getByText('Not participating (50)')).toBeInTheDocument();
  });

  it('still welcomes an admin whose workspace really is empty', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({
      data: {
        items: [],
        total: 0,
        page: 0,
        size: 20,
        facets: {
          totalUnfiltered: 0,
          roleAny: 0,
          roleOwner: 0,
          roleReviewer: 0,
          roleObserver: 0,
          stateActive: 0,
          stateOpen: 0,
          stateClosed: 0,
          stateArchived: 0,
          stateAll: 0,
        },
      },
    });

    renderPage('/reviews?participation=all');

    expect(screen.getByText(/Start your first review/i)).toBeInTheDocument();
  });

  it('sends the advanced filters to the server too', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [foreign], total: 57, page: 0, size: 20, facets: FACETS } });

    renderPage('/reviews?participation=all&due=overdue&format=docx&state=CHANGES_REQUESTED');

    // Everything the filter button offers has to reach the query, or it would
    // narrow the page while reading as the workspace — the same trap the chips
    // had (issue #563).
    const sent = vi.mocked(useReviews).mock.calls.at(-1)![0];
    expect(sent.due).toBe('overdue');
    expect(sent.format).toBe('docx');
    expect(sent.workflowState).toBe('CHANGES_REQUESTED');
  });

  it('offers every owner in the workspace, not just the ones on this page', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({
      data: {
        items: [foreign],
        total: 57,
        page: 0,
        size: 20,
        facets: {
          ...FACETS,
          owners: [
            { id: OTHER, displayName: 'Someone Else' },
            { id: 'owner-3', displayName: 'Nobody On This Page' },
          ],
        },
      },
    });

    renderPage('/reviews?participation=all');
    fireEvent.click(screen.getByRole('button', { name: 'Filter reviews' }));

    // The page holds one row owned by one person; the facet still offers both,
    // because it comes from the server.
    expect(screen.getByText('Nobody On This Page')).toBeInTheDocument();
  });

  it('selects rows and offers to delete them together', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });

    renderPage();

    // No selection, no bar: an empty toolbar taking up room says nothing.
    expect(screen.queryByTestId('bulk-actions')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select NDA Acme Corp' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Architecture handbook' }));

    const bar = screen.getByTestId('bulk-actions');
    expect(within(bar).getByText('2 selected')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: /Delete selected \(2\)/ })).toBeEnabled();
  });

  it('names every review the bulk delete would take', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });

    renderPage();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select NDA Acme Corp' }));
    fireEvent.click(screen.getByRole('button', { name: /Delete selected/ }));

    // A selection made across a filtered listing is easy to misjudge; the titles
    // are what catch the row that should not be there (issue #421).
    const list = screen.getByTestId('bulk-delete-list');
    expect(within(list).getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Delete 1 permanently/ })).toBeDisabled();
  });

  it('selects and clears every row on the page at once', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });

    renderPage();
    const all = screen.getByRole('checkbox', { name: 'Select every review on this page' });
    fireEvent.click(all);
    // Three of the four fixtures are live work; the archived one is not shown by
    // default, so "every row on this page" means exactly what is on screen.
    expect(within(screen.getByTestId('bulk-actions')).getByText('3 selected')).toBeInTheDocument();

    fireEvent.click(all);
    expect(screen.queryByTestId('bulk-actions')).not.toBeInTheDocument();
  });

  it('offers no selection to someone who may not delete', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'MEMBER' });

    renderPage();

    // Deleting is admin-only, so a checkbox column would promise something the
    // server refuses.
    expect(screen.queryByRole('checkbox', { name: /^Select / })).not.toBeInTheDocument();
  });

  it('says which slice of the workspace is on screen', () => {
    useAuthStore.setState({ userId: ME, isAuthenticated: true, role: 'ADMIN' });
    mockReviews({ data: { items: [foreign], total: 57, page: 0, size: 20, facets: FACETS } });

    renderPage('/reviews?participation=all');

    // A moderator has to know whether this is everything or page one of three.
    expect(screen.getByText(/1–20 of 57/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
  });
});
