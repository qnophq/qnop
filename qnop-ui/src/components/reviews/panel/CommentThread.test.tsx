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
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { CommentThread } from './CommentThread';
import { useAddComment, useComments } from '../../../api/hooks/useComments';

vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn(),
  useAddComment: vi.fn(),
}));

const addMutate = vi.fn();

function renderThread() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <CommentThread annotationId="a1" notify={vi.fn()} />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: 'me' });
  vi.mocked(useAddComment).mockReturnValue({
    mutate: addMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useAddComment>);
});

describe('CommentThread', () => {
  it('renders the thread with authorship relative to the signed-in user', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        comments: [
          {
            id: 'c1',
            annotationId: 'a1',
            authorId: 'me',
            body: 'Mine',
            createdAt: '2026-07-01T10:00:00Z',
          },
          {
            id: 'c2',
            annotationId: 'a1',
            authorId: 'other',
            body: 'Theirs',
            createdAt: '2026-07-01T11:00:00Z',
          },
        ],
      },
    } as unknown as ReturnType<typeof useComments>);

    renderThread();

    expect(screen.getByText('You')).toBeInTheDocument();
    expect(screen.getByText('Participant')).toBeInTheDocument();
    expect(screen.getByText('Mine')).toBeInTheDocument();
    expect(screen.getByText('Theirs')).toBeInTheDocument();
  });

  it('shows the empty state', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: { comments: [] },
    } as unknown as ReturnType<typeof useComments>);

    renderThread();

    expect(screen.getByText('No comments yet.')).toBeInTheDocument();
  });

  it('submits a trimmed comment and blocks empty drafts', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: { comments: [] },
    } as unknown as ReturnType<typeof useComments>);

    renderThread();

    const button = screen.getByRole('button', { name: 'Comment' });
    expect(button).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Add a comment'), {
      target: { value: '  Needs a stronger clause  ' },
    });
    fireEvent.click(button);

    expect(addMutate).toHaveBeenCalledWith('Needs a stronger clause', expect.anything());
  });
});
