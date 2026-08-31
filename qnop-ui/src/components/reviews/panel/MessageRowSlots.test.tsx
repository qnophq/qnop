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

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { ExtensionsProvider } from '../../../extensions/ExtensionsProvider';
import {
  createExtensionRegistry,
  type ExtensionRegistry,
  type MessageRowContext,
} from '../../../extensions/registry';
import { CommentMessage } from './CommentMessage';
import { AnnotationHead } from './AnnotationHead';

// The head reads the opening comment from the thread cache and the review for
// the anonymity gate + workflow state; both are steered per test.
vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({
    data: {
      comments: [
        {
          id: 'c-open',
          authorId: 'u-author',
          body: 'Opening words',
          createdAt: '2026-08-01T10:00:00Z',
          reactions: [],
        },
      ],
    },
  }),
}));
vi.mock('../../../api/hooks/useDocuments', () => ({
  useDocument: vi.fn().mockReturnValue({
    data: { id: 'd1', anonymous: false, ownerId: 'u-owner', workflowState: 'IN_REVIEW' },
  }),
}));
vi.mock('../reviewDocumentId', () => ({ useReviewDocumentId: () => 'd1' }));
vi.mock('../reactions/useReactions', () => ({
  useToggleAnnotationReaction: () => ({ mutate: vi.fn() }),
}));

/** A fake extension: one action button and one badge, both printing their context. */
function fakeRegistry(): ExtensionRegistry {
  const registry = createExtensionRegistry();
  registry.register('messageActions', {
    id: 'fake-action',
    // The contract expects an accessible name on interactive output — query
    // it by role+name below, the convention contributors should copy.
    render: (ctx: MessageRowContext) => (
      <button aria-label="Edit message" data-testid={`ext-action-${ctx.kind}`}>
        {`${ctx.commentId}|${ctx.annotationId}|${ctx.own}|${ctx.annotationStatus}|${ctx.workflowState}|${ctx.reviewOpen}`}
      </button>
    ),
  });
  registry.register('messageBadges', {
    id: 'fake-badge',
    render: (ctx: MessageRowContext) => <span data-testid={`ext-badge-${ctx.kind}`}>edited</span>,
  });
  return registry;
}

function renderWith(registry: ExtensionRegistry | null, ui: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>{ui}</MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
  return render(
    registry ? <ExtensionsProvider registry={registry}>{tree}</ExtensionsProvider> : tree,
  );
}

const commentContext: MessageRowContext = {
  kind: 'comment',
  documentId: 'd1',
  annotationId: 'a1',
  commentId: 'c9',
  authorId: 'u-author',
  own: true,
  annotationStatus: AnnotationStatus.Open,
  workflowState: 'IN_REVIEW',
  reviewOpen: true,
  body: 'Hello',
};

const annotation = {
  id: 'a1',
  documentId: 'd1',
  authorId: 'u-author',
  status: AnnotationStatus.Open,
  firstComment: 'Opening words',
  commentCount: 1,
  reactions: [],
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-01T10:00:00Z',
} as unknown as AnnotationView;

// Issue #600: the community rows carry an actions and a badge extension slot;
// a registered contribution renders with the documented message context, and
// without one the rows render exactly as before.
describe('message-row extension slots (#600)', () => {
  it('renders a contributed action and badge on a comment row with its context', () => {
    renderWith(
      fakeRegistry(),
      <CommentMessage
        name="Anna Weber"
        own
        avatarUrl={null}
        body="Hello"
        createdAt="2026-08-01T10:00:00Z"
        slotContext={commentContext}
      />,
    );

    expect(screen.getByRole('button', { name: 'Edit message' })).toHaveTextContent(
      'c9|a1|true|OPEN|IN_REVIEW|true',
    );
    expect(screen.getByTestId('ext-badge-comment')).toHaveTextContent('edited');
  });

  it('renders no contribution artifacts without a registered extension', () => {
    const { container } = renderWith(
      null,
      <CommentMessage
        name="Anna Weber"
        own={false}
        avatarUrl={null}
        body="Hello"
        createdAt="2026-08-01T10:00:00Z"
        slotContext={commentContext}
      />,
    );

    expect(screen.queryByTestId('ext-action-comment')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ext-badge-comment')).not.toBeInTheDocument();
    // Without notify/reactions/contributions the hover-action container itself
    // stays absent — the row is byte-identical to the pre-#600 DOM.
    expect(container.querySelector('.comment-hover-actions')).not.toBeInTheDocument();
  });

  it('skips contributions entirely when the caller has no context (hover preview)', () => {
    renderWith(
      fakeRegistry(),
      <CommentMessage
        name="Anna Weber"
        own={false}
        avatarUrl={null}
        body="Hello"
        createdAt="2026-08-01T10:00:00Z"
      />,
    );

    expect(screen.queryByTestId('ext-action-comment')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ext-badge-comment')).not.toBeInTheDocument();
  });

  it('renders contributions on the annotation head with the opening-comment context', () => {
    useAuthStore.setState({ userId: 'u-author' });

    renderWith(fakeRegistry(), <AnnotationHead annotation={annotation} />);

    // kind=annotation, the cached opening comment id, own (viewer = author),
    // the annotation status and the review workflow state, still open.
    expect(screen.getByTestId('ext-action-annotation')).toHaveTextContent(
      'c-open|a1|true|OPEN|IN_REVIEW|true',
    );
    expect(screen.getByTestId('ext-badge-annotation')).toHaveTextContent('edited');
  });

  it('keeps the annotation head free of artifacts without extensions', () => {
    useAuthStore.setState({ userId: 'u-viewer' });
    const { container } = renderWith(null, <AnnotationHead annotation={annotation} />);

    expect(screen.queryByTestId('ext-action-annotation')).not.toBeInTheDocument();
    // No notify and no contributions: the hover-action container is absent.
    expect(container.querySelector('.annotation-hover-actions')).not.toBeInTheDocument();
  });
});
