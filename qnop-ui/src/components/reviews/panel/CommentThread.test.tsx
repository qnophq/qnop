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

function renderThread(readOnly = false, previousSeenAt: string | null = null, closed = false) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <CommentThread
        annotationId="a1"
        notify={vi.fn()}
        readOnly={readOnly}
        closed={closed}
        previousSeenAt={previousSeenAt}
      />
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
  it('separates foreign comments newer than the previous visit with a divider', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        comments: [
          {
            id: 'c1',
            annotationId: 'a1',
            authorId: 'other',
            body: 'Old',
            createdAt: '2026-07-01T10:00:00Z',
          },
          {
            id: 'c2',
            annotationId: 'a1',
            authorId: 'me',
            body: 'Mine, new',
            createdAt: '2026-07-03T10:00:00Z',
          },
          {
            id: 'c3',
            annotationId: 'a1',
            authorId: 'other',
            body: 'Theirs, new',
            createdAt: '2026-07-04T10:00:00Z',
          },
        ],
      },
    } as unknown as ReturnType<typeof useComments>);

    renderThread(false, '2026-07-02T00:00:00Z');

    // Exactly one divider, sitting before the first NEW FOREIGN comment —
    // the user's own newer reply never triggers it (issue #307).
    expect(screen.getAllByTestId('new-since-divider')).toHaveLength(1);
    expect(screen.getByText('New since your last visit')).toBeInTheDocument();
  });

  it('shows no divider on a first visit', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        comments: [
          {
            id: 'c1',
            annotationId: 'a1',
            authorId: 'other',
            body: 'Hi',
            createdAt: '2026-07-04T10:00:00Z',
          },
        ],
      },
    } as unknown as ReturnType<typeof useComments>);

    renderThread(false, null);
    expect(screen.queryByTestId('new-since-divider')).not.toBeInTheDocument();
  });

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

    expect(screen.getByText(/No comments yet/)).toBeInTheDocument();
  });

  it('replaces the composer with a read-only note under a READ_ONLY policy (issue #413)', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: { comments: [] },
    } as unknown as ReturnType<typeof useComments>);

    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread annotationId="a1" notify={vi.fn()} policyReadOnly />
      </ThemeProvider>,
    );

    expect(screen.getByTestId('thread-policy-readonly-note')).toBeInTheDocument();
    expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument();
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

  it('hides the reply composer on a read-only (older) version (#306)', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: { comments: [] },
    } as unknown as ReturnType<typeof useComments>);
    renderThread(true);
    expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument();
    // The closing line belongs to the resolved state, not to read-only.
    expect(screen.queryByTestId('thread-closed-note')).not.toBeInTheDocument();
  });

  it('closes a resolved thread: no composer, a quiet closing line instead (#403)', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: { comments: [] },
    } as unknown as ReturnType<typeof useComments>);
    renderThread(false, null, true);
    expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument();
    expect(screen.getByTestId('thread-closed-note')).toHaveTextContent(
      'Resolved — this thread is closed.',
    );
    // Reopening is offered only when the caller wires it (author, running review).
    expect(screen.queryByRole('button', { name: 'Reopen' })).not.toBeInTheDocument();
  });

  it('drops the "yet" once a resolved thread has no replies (#403)', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        comments: [
          {
            id: 'c1',
            annotationId: 'a1',
            authorId: 'me',
            body: 'opener',
            createdAt: '2026-07-01T10:00:00Z',
          },
        ],
      },
    } as unknown as ReturnType<typeof useComments>);
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread annotationId="a1" notify={vi.fn()} closed skipOpener />
      </ThemeProvider>,
    );
    expect(screen.getByText('No replies.')).toBeInTheDocument();
    expect(screen.queryByText('No replies yet.')).not.toBeInTheDocument();
  });

  it('offers Reopen on a closed thread when wired, and forwards the click (#394)', () => {
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: { comments: [] },
    } as unknown as ReturnType<typeof useComments>);
    const onReopen = vi.fn();
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread annotationId="a1" notify={vi.fn()} closed onReopen={onReopen} />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Reopen' }));
    expect(onReopen).toHaveBeenCalled();
  });
});

// Issue #412: permalinks. The thread carries a per-comment copy affordance and
// resolves an incoming comment permalink target — scroll into view + pulse, or
// a toast when the id is gone.
describe('CommentThread permalinks (#412)', () => {
  const TWO_COMMENTS = {
    isPending: false,
    isError: false,
    data: {
      comments: [
        {
          id: 'c1',
          annotationId: 'a1',
          authorId: 'me',
          body: 'first',
          createdAt: '2026-07-01T10:00:00Z',
        },
        {
          id: 'c2',
          annotationId: 'a1',
          authorId: 'other',
          body: 'second',
          createdAt: '2026-07-02T10:00:00Z',
        },
      ],
    },
  } as unknown as ReturnType<typeof useComments>;

  const buildPermalink = (annotationId: string, commentId?: string) =>
    `https://qnop.example/reviews/d?annotation=${annotationId}${
      commentId ? `&comment=${commentId}` : ''
    }`;

  it('renders a copy-link affordance on every comment when a builder is provided', () => {
    vi.mocked(useComments).mockReturnValue(TWO_COMMENTS);
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread annotationId="a1" notify={vi.fn()} buildPermalink={buildPermalink} />
      </ThemeProvider>,
    );
    expect(screen.getAllByRole('button', { name: 'Copy link to comment' })).toHaveLength(2);
  });

  it('shows no per-comment copy affordance without a builder (e.g. the hover preview)', () => {
    vi.mocked(useComments).mockReturnValue(TWO_COMMENTS);
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread annotationId="a1" notify={vi.fn()} />
      </ThemeProvider>,
    );
    expect(screen.queryByRole('button', { name: 'Copy link to comment' })).not.toBeInTheDocument();
  });

  it('scrolls a comment permalink target into view and consumes it once', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    vi.mocked(useComments).mockReturnValue(TWO_COMMENTS);
    const notify = vi.fn();
    const onScrolledToComment = vi.fn();
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread
          annotationId="a1"
          notify={notify}
          scrollToCommentId="c2"
          onScrolledToComment={onScrolledToComment}
        />
      </ThemeProvider>,
    );
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(onScrolledToComment).toHaveBeenCalledTimes(1);
    expect(notify).not.toHaveBeenCalled();
  });

  it('degrades an unknown comment permalink target to a toast', () => {
    vi.mocked(useComments).mockReturnValue(TWO_COMMENTS);
    const notify = vi.fn();
    const onScrolledToComment = vi.fn();
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <CommentThread
          annotationId="a1"
          notify={notify}
          scrollToCommentId="ghost"
          onScrolledToComment={onScrolledToComment}
        />
      </ThemeProvider>,
    );
    expect(notify).toHaveBeenCalledWith('This comment no longer exists.', 'error');
    expect(onScrolledToComment).toHaveBeenCalledTimes(1);
  });
});
