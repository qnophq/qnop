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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { FocusAnnotationCard } from './FocusAnnotationCard';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('../panel/CommentThread', () => ({
  CommentThread: () => <div data-testid="thread" />,
}));
vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: false, isError: false, data: undefined }),
}));
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useConfirmPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useReattachPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useResolveAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
  useReopenAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
  useDismissAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
}));

const ANNOTATION: AnnotationView = {
  id: 'a2',
  documentId: 'd1',
  authorId: 'author-1',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Moved,
  anchor: {
    region: { surfaceIndex: 0, box: { x: 0.1, y: 0.2, width: 0.3, height: 0.02 } },
    textQuote: { quote: 'die strittige Klausel' },
  },
  commentCount: 2,
  reactions: [],
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
};

const originalClipboard = navigator.clipboard;
let anchor: HTMLElement;

/** Focus mode in German (issue #836): the walk, the head's permalink and reaction affordances. */
describe('focus card in German', () => {
  beforeEach(async () => {
    anchor = document.createElement('button');
    document.body.appendChild(anchor);
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    anchor.remove();
    Object.assign(navigator, { clipboard: originalClipboard });
    await applyLocale('en');
  });

  it('walks, closes, links and reacts in German', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const notify = vi.fn();
    render(
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={buildTheme('light', 'de')}>
          <FocusAnnotationCard
            annotation={ANNOTATION}
            anchorEl={anchor}
            position={{ index: 1, count: 3, prevId: 'a1', nextId: 'a3' }}
            onNavigate={vi.fn()}
            onClose={vi.fn()}
            userId="author-1"
            notify={notify}
            buildPermalink={(id) => `https://qnop.example/reviews/d1?annotation=${id}`}
          />
        </ThemeProvider>
      </QueryClientProvider>,
    );

    expect(screen.getByText('Anmerkung 2 von 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Vorherige Anmerkung' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nächste Anmerkung' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Anmerkung schließen' })).toBeInTheDocument();
    expect(screen.getByText('Verschoben')).toBeInTheDocument();
    expect(screen.getByText('„die strittige Klausel“')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zitat kopieren' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reaktion hinzufügen' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erledigen' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Link zur Anmerkung kopieren' }));
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Link kopiert.', 'success'));
  });
});
