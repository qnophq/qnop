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
import { render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { AnnotationHoverCard } from './AnnotationHoverCard';
import { useComments } from '../../../api/hooks/useComments';

vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn(),
}));

const annotation = (overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id: 'a1',
  documentId: 'd1',
  authorId: 'other',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Moved,
  anchor: {
    region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'quoted text' },
  },
  commentCount: 3,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

function renderCard(view: AnnotationView) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <AnnotationHoverCard annotation={view} getAnchorPosition={() => ({ left: 120, top: 240 })} />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: 'me', displayName: 'Martin', avatarUrl: null });
});

describe('AnnotationHoverCard', () => {
  it('previews author, first comment, status, placement cue and thread size', async () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        comments: [
          {
            id: 'c1',
            annotationId: 'a1',
            authorId: 'other',
            body: 'Please tighten this clause.',
            createdAt: '2026-07-01T11:00:00Z',
          },
        ],
      },
    } as unknown as ReturnType<typeof useComments>);

    renderCard(annotation());

    await waitFor(() =>
      expect(screen.getByText('Please tighten this clause.')).toBeInTheDocument(),
    );
    expect(screen.getByText('Participant')).toBeInTheDocument();
    expect(screen.getByText('Open')).toBeInTheDocument();
    expect(screen.getByText('Moved')).toBeInTheDocument();
    expect(screen.getByText('3 comments')).toBeInTheDocument();
    expect(useComments).toHaveBeenCalledWith('a1', true);
  });

  it('shows the empty state without fetching when there are no comments', async () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: undefined,
    } as unknown as ReturnType<typeof useComments>);

    renderCard(annotation({ commentCount: 0, placementStatus: PlacementStatus.Placed }));

    await waitFor(() => expect(screen.getByText('No comments yet.')).toBeInTheDocument());
    expect(useComments).toHaveBeenCalledWith('a1', false);
    expect(screen.getByText('0 comments')).toBeInTheDocument();
  });
});
