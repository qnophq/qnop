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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../api/generated';
import {
  AnnotationPriority,
  AnnotationStatus,
  AnnotationType,
  PlacementStatus,
} from '../../api/generated';
import { useAnnotations } from '../../api/hooks/useAnnotations';
import { useDocument, useDocumentVersions } from '../../api/hooks/useDocuments';
import { useParticipants } from '../../api/hooks/useReviews';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { ReviewTasksPage } from './ReviewTasksPage';

vi.mock('../../api/hooks/useDocuments', () => ({
  useDocument: vi.fn(),
  useDocumentVersions: vi.fn(),
}));
vi.mock('../../api/hooks/useAnnotations', () => ({
  useAnnotations: vi.fn(),
  useResolveAnnotation: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useReopenAnnotation: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
}));
vi.mock('../../api/hooks/useReviews', () => ({
  useParticipants: vi.fn(),
  useRecordVisit: vi.fn().mockReturnValue(null),
}));
vi.mock('../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: true, isError: false, data: undefined }),
  useAddComment: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
}));

const annotation = (id: string, overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'author-1',
  // Author name is resolved server-side and travels on the annotation now (#413).
  authorDisplayName: 'Sabine Weber',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  anchor: {
    region: { surfaceIndex: 1, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'liability clause' },
  },
  type: AnnotationType.Conflict,
  priority: AnnotationPriority.High,
  firstComment: `Concern ${id}`,
  commentCount: 1,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

function mockData() {
  vi.mocked(useDocument).mockReturnValue({
    isPending: false,
    isError: false,
    data: { id: 'd1', title: 'Supply Contract', ownerId: 'owner-1', latestVersionNumber: 2 },
  } as never);
  vi.mocked(useDocumentVersions).mockReturnValue({
    data: { versions: [{ versionNumber: 1 }, { versionNumber: 2 }] },
  } as never);
  vi.mocked(useAnnotations).mockReturnValue({
    isPending: false,
    data: {
      annotations: [
        annotation('a-open'),
        annotation('a-talk', { commentCount: 4, type: AnnotationType.Question }),
        annotation('a-done', { status: AnnotationStatus.Resolved }),
      ],
    },
  } as never);
  vi.mocked(useParticipants).mockReturnValue({
    data: { participants: [{ principalId: 'author-1', displayName: 'Sabine Weber' }] },
  } as never);
}

function renderPage(initialEntry = '/reviews/d1/tasks') {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/reviews/:documentId/tasks" element={<ReviewTasksPage />} />
          <Route path="/reviews/:documentId" element={<div data-testid="review-page" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: 'owner-1', displayName: 'Maxim Owner' });
});

afterEach(() => {
  useAuthStore.setState({ userId: null, displayName: null });
  localStorage.removeItem('qnop-tasks-view');
});

describe('ReviewTasksPage', () => {
  it('renders the board with derived columns and filter counts', () => {
    mockData();
    renderPage();

    expect(
      within(screen.getByTestId('task-column-open')).getByTestId('task-card-a-open'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId('task-column-discussion')).getByTestId('task-card-a-talk'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId('task-column-done')).getByTestId('task-card-a-done'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('task-filter-all')).toHaveTextContent('All 3');
    expect(screen.getByTestId('task-filter-discussion')).toHaveTextContent('In discussion 1');
  });

  it('filters by status chip and by search', () => {
    mockData();
    renderPage('/reviews/d1/tasks?filter=done');
    expect(screen.queryByTestId('task-card-a-open')).not.toBeInTheDocument();
    expect(screen.getByTestId('task-card-a-done')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('task-filter-all'));
    fireEvent.change(screen.getByLabelText('Search tasks'), { target: { value: 'a-talk' } });
    expect(screen.getByTestId('task-card-a-talk')).toBeInTheDocument();
    expect(screen.queryByTestId('task-card-a-open')).not.toBeInTheDocument();
  });

  it('filters by the type facet from the URL, with a removable chip', () => {
    mockData();
    renderPage('/reviews/d1/tasks?type=QUESTION');
    expect(screen.getByTestId('task-card-a-talk')).toBeInTheDocument();
    expect(screen.queryByTestId('task-card-a-open')).not.toBeInTheDocument();
    expect(screen.getByTestId('active-filter-chips')).toHaveTextContent('Question');
  });

  it('offers the facet popover without the status facet (columns speak status)', () => {
    mockData();
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Filter annotations' }));
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument();

    fireEvent.mouseDown(screen.getByLabelText('Type'));
    fireEvent.click(screen.getByRole('option', { name: 'Question' }));
    expect(screen.getByTestId('task-card-a-talk')).toBeInTheDocument();
    expect(screen.queryByTestId('task-card-a-done')).not.toBeInTheDocument();
  });

  it('shows the server-resolved author name, and self by display name (issue #413)', () => {
    mockData();
    vi.mocked(useAnnotations).mockReturnValue({
      isPending: false,
      data: { annotations: [annotation('a-open'), annotation('a-mine', { authorId: 'owner-1' })] },
    } as never);
    renderPage();
    expect(
      within(screen.getByTestId('task-card-a-open')).getByText('Sabine Weber'),
    ).toBeInTheDocument();
    // Own contributions read "You"-side name from the auth store, not the payload.
    expect(
      within(screen.getByTestId('task-card-a-mine')).getByText('Maxim Owner'),
    ).toBeInTheDocument();
  });

  it('drops the author facet in an anonymous review (issue #413)', () => {
    mockData();
    vi.mocked(useDocument).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        id: 'd1',
        title: 'Supply Contract',
        ownerId: 'owner-1',
        latestVersionNumber: 2,
        anonymous: true,
      },
    } as never);
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Filter annotations' }));
    expect(screen.queryByLabelText('Author')).not.toBeInTheDocument();
  });

  it('opens the drawer from a card and jumps to the document with the deep link', () => {
    mockData();
    renderPage();

    fireEvent.click(screen.getByTestId('task-card-a-open'));
    const drawer = screen.getByTestId('task-drawer');
    expect(within(drawer).getByText('Concern a-open')).toBeInTheDocument();

    fireEvent.click(within(drawer).getByRole('button', { name: /Show in document/ }));
    expect(screen.getByTestId('review-page')).toBeInTheDocument();
  });

  it('honours the stored list preference', () => {
    localStorage.setItem('qnop-tasks-view', 'list');
    mockData();
    renderPage();
    expect(screen.getByTestId('task-row-a-open')).toBeInTheDocument();
    expect(screen.queryByTestId('task-board')).not.toBeInTheDocument();
  });
});
