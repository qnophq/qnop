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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { DocumentSummary } from '../../../api/generated';
import { ParticipantKind } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { DeleteReviewsDialog } from '../DeleteReviewsDialog';
import { WorkflowMilestones } from '../WorkflowMilestones';
import { ReviewsTable } from './ReviewsTable';

vi.mock('../../../api/config', () => ({
  axiosInstance: { post: vi.fn(), get: vi.fn() },
  documentsApi: { deleteDocument: vi.fn() },
}));

const ME = '00000000-0000-0000-0000-0000000000aa';

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

function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{ui}</MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

/** The reviews overview in German (issue #836): headers, roles, workflow badges and plurals. */
describe('reviews list in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the table with German headers, roles and workflow badges', () => {
    renderWithProviders(
      <ReviewsTable
        reviews={[
          summary({
            id: 'doc-1',
            title: 'NDA Acme Corp',
            workflowState: 'IN_REVIEW',
            annotationCount: 3,
            openAnnotationCount: 1,
            participants: [
              { id: 'p1', kind: ParticipantKind.User, principalId: 'u-max', displayName: 'Max' },
            ],
          }),
          summary({
            id: 'doc-2',
            title: 'Final contract',
            ownerId: 'someone-else',
            workflowState: 'FINALIZED',
            archivedAt: '2026-07-05T10:00:00Z',
          }),
        ]}
        userId={ME}
        onOpen={() => undefined}
        onDelete={() => undefined}
      />,
    );

    expect(screen.getByRole('columnheader', { name: 'Fortschritt' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Aktionen' })).toBeInTheDocument();

    const first = within(screen.getByTestId('review-row-doc-1'));
    expect(first.getByText('Im Review')).toBeInTheDocument();
    expect(first.getAllByText('Review-Leitung').length).toBeGreaterThan(0);
    expect(first.getByRole('progressbar', { name: '2 von 3 Anmerkungen erledigt' })).toBeTruthy();
    // The review title is server data: interpolated as-is, never translated.
    expect(first.getByRole('button', { name: 'NDA Acme Corp löschen' })).toBeInTheDocument();

    const second = within(screen.getByTestId('review-row-doc-2'));
    expect(second.getByText('Archiviert · Abgeschlossen')).toBeInTheDocument();
    expect(second.getByText('Reviewer')).toBeInTheDocument();
    expect(second.getByText('keine Reviewer')).toBeInTheDocument();
  });

  it('pluralizes the bulk-delete dialog and asks for the German confirmation word', () => {
    renderWithProviders(
      <DeleteReviewsDialog
        reviews={[
          { id: 'a', title: 'First' },
          { id: 'b', title: 'Second' },
        ]}
        open
        onClose={() => undefined}
        onDeleted={() => undefined}
      />,
    );

    expect(screen.getByText('2 Reviews löschen?')).toBeInTheDocument();
    expect(screen.getByLabelText('Gib zur Bestätigung „löschen“ ein')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '2 endgültig löschen' })).toBeDisabled();
  });

  it('summarizes the milestone path with a plural', () => {
    renderWithProviders(<WorkflowMilestones state="IN_REVIEW" total={3} resolved={1} />);

    expect(
      screen.getByRole('img', {
        name: 'Im Review — 1 von 3 Anmerkungen erledigt, noch 2 offen',
      }),
    ).toBeInTheDocument();
  });
});
