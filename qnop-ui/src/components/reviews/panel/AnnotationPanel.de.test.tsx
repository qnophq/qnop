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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router';
import type { AnnotationView } from '../../../api/generated';
import {
  AnnotationPriority,
  AnnotationStatus,
  AnnotationType,
  PlacementStatus,
} from '../../../api/generated';
import { useParticipants } from '../../../api/hooks/useReviews';
import { useDocument } from '../../../api/hooks/useDocuments';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { AnnotationPanel } from './AnnotationPanel';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('./CommentThread', () => ({
  CommentThread: ({ annotationId }: { annotationId: string }) => (
    <div data-testid={`thread-${annotationId}`} />
  ),
}));
vi.mock('../../../api/hooks/useDocuments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/hooks/useDocuments')>()),
  useDocument: vi.fn(),
}));
vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: false, isError: false, data: undefined }),
}));
vi.mock('../../../api/hooks/useReviews', () => ({
  useParticipants: vi.fn(),
}));
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useConfirmPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useReattachPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useResolveAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
  useReopenAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
  useDismissAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
}));

const annotation = (id: string, overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'u1',
  authorDisplayName: 'Anna Weber',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  anchor: {
    region: { surfaceIndex: 1, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'die strittige Klausel' },
  },
  commentCount: 1,
  reactions: [],
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

function renderPanel(props: Partial<Parameters<typeof AnnotationPanel>[0]> = {}) {
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light', 'de')}>
        <MemoryRouter>
          <AnnotationPanel
            annotations={[]}
            activeAnnotationId={null}
            onSelect={vi.fn()}
            pendingAnchor={null}
            creating={false}
            onCreate={vi.fn()}
            onCancelPending={vi.fn()}
            canAnnotate
            notify={vi.fn()}
            {...props}
          />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

/** The annotation panel in German (issue #836): counts, cues, filters and empty states. */
describe('AnnotationPanel in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
    vi.mocked(useDocument).mockReturnValue({ data: undefined } as never);
    vi.mocked(useParticipants).mockReturnValue({ data: { participants: [] } } as never);
    useAuthStore.setState({ userId: null });
    localStorage.clear();
  });
  afterEach(async () => {
    // Unmount first, so switching back to English re-renders nothing outside act().
    cleanup();
    await applyLocale('en');
  });

  it('renders the header, status, type, placement and plural comment counts', () => {
    renderPanel({
      versionNumber: 2,
      annotations: [
        annotation('a1', {
          type: AnnotationType.Change,
          priority: AnnotationPriority.High,
        }),
        annotation('a2', { status: AnnotationStatus.Resolved, commentCount: 3 }),
        annotation('a3', { placementStatus: PlacementStatus.Moved, commentCount: 2 }),
      ],
    });

    expect(screen.getByText('Anmerkungen (3)')).toBeInTheDocument();
    expect(screen.getByText('Markierungen und ihre Diskussion in dieser Version.')).toBeVisible();
    // Status tiles name themselves (icon + tooltip trigger).
    expect(screen.getAllByLabelText('Offen').length).toBeGreaterThan(0);
    expect(screen.getAllByLabelText('Erledigt').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('1 Kommentar')).toBeInTheDocument();
    expect(screen.getByLabelText('3 Kommentare')).toBeInTheDocument();
    expect(screen.getByText('Änderung')).toBeInTheDocument();
    expect(screen.getByText('Verschoben')).toBeInTheDocument();
    expect(screen.getAllByText('„die strittige Klausel“')).toHaveLength(3);
    expect(screen.getAllByText('S. 2')).toHaveLength(3);
    expect(screen.getByText('Neuverankerung in v2: 1 verschoben.')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Anmerkungen durchsuchen')).toBeInTheDocument();
  });

  it('labels the filter facets and their options in German', () => {
    renderPanel({ annotations: [annotation('a1')] });

    fireEvent.click(screen.getByRole('button', { name: 'Anmerkungen filtern' }));
    for (const label of ['Status', 'Position', 'Typ', 'Priorität', 'Verfasser']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
    fireEvent.mouseDown(screen.getByLabelText('Position'));
    fireEvent.click(screen.getByRole('option', { name: 'Braucht Aufmerksamkeit' }));

    expect(screen.getByText('Keine Anmerkungen passen zu diesem Filter.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Filter zurücksetzen' })).toBeInTheDocument();
  });

  it('invites the first annotation in German', () => {
    renderPanel();

    expect(
      screen.getByText(
        'Noch keine Anmerkungen. Markiere Text oder ziehe einen Bereich im Dokument auf, um eine hinzuzufügen.',
      ),
    ).toBeInTheDocument();
  });
});
