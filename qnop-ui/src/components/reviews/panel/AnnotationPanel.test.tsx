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
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { AnnotationPanel } from './AnnotationPanel';

vi.mock('./CommentThread', () => ({
  CommentThread: ({
    annotationId,
    closed,
    onReopen,
  }: {
    annotationId: string;
    closed?: boolean;
    onReopen?: () => void;
  }) => (
    <div
      data-testid={`thread-${annotationId}`}
      data-closed={closed ? 'true' : 'false'}
      data-can-reopen={onReopen ? 'true' : 'false'}
    />
  ),
}));

vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: false, isError: false, data: undefined }),
}));

const { resolveMutate } = vi.hoisted(() => ({ resolveMutate: vi.fn() }));
vi.mock('../../../api/hooks/useReviews', () => ({
  useParticipants: vi.fn().mockReturnValue({
    data: { participants: [{ principalId: 'other', displayName: 'Anna Weber' }] },
  }),
}));
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useResolveAnnotation: () => ({ mutate: resolveMutate, isPending: false }),
  useReopenAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
}));

beforeEach(() => {
  resolveMutate.mockClear();
  useAuthStore.setState({ userId: null });
});

const annotation = (id: string, overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'u1',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  anchor: {
    region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'quoted text' },
  },
  commentCount: 2,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

function renderPanel(props: Partial<Parameters<typeof AnnotationPanel>[0]> = {}) {
  const defaults: Parameters<typeof AnnotationPanel>[0] = {
    annotations: [],
    activeAnnotationId: null,
    onSelect: vi.fn(),
    pendingAnchor: null,
    creating: false,
    onCreate: vi.fn(),
    onCancelPending: vi.fn(),
    canAnnotate: true,
    notify: vi.fn(),
  };
  const merged = { ...defaults, ...props };
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <AnnotationPanel {...merged} />
    </ThemeProvider>,
  );
  return merged;
}

describe('AnnotationPanel', () => {
  it('marks unseen foreign activity and counts it in the section header', () => {
    useAuthStore.setState({ userId: 'me' });
    renderPanel({
      previousSeenAt: '2026-07-02T00:00:00Z',
      annotations: [
        // Foreign and new → dot; own and new → no dot; old with a fresh foreign reply → dot.
        annotation('a-new', { authorId: 'other', createdAt: '2026-07-03T10:00:00Z' }),
        annotation('a-mine', { authorId: 'me', createdAt: '2026-07-03T10:00:00Z' }),
        annotation('a-replied', {
          authorId: 'me',
          createdAt: '2026-07-01T10:00:00Z',
          latestCommentFromOthersAt: '2026-07-03T12:00:00Z',
        }),
      ],
    });

    expect(
      within(screen.getByTestId('annotation-item-a-new')).getByTestId('unseen-dot'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId('annotation-item-a-mine')).queryByTestId('unseen-dot'),
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByTestId('annotation-item-a-replied')).getByTestId('unseen-dot'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('section-new-count')).toHaveTextContent('2 new');
  });

  it('shows the empty state with the how-to hint when annotating is possible', () => {
    renderPanel();
    expect(
      screen.getByText(/No annotations yet\. Select text or draw a region/),
    ).toBeInTheDocument();
  });

  it('separates document-scoped annotations into their own group (#395)', () => {
    renderPanel({
      annotations: [
        annotation('placed-1'),
        // A document-scoped annotation: no anchor and no placement (issue #395).
        annotation('doc-1', { anchor: undefined, placementStatus: undefined }),
      ],
      activeAnnotationId: 'doc-1',
    });

    expect(screen.getByText('Annotations (2)')).toBeInTheDocument();
    // The anchor-free annotation groups under "Whole document", not the "anchor lost" bucket.
    expect(screen.getByText('Whole document')).toBeInTheDocument();
    expect(screen.queryByText('Not placed on this version')).not.toBeInTheDocument();
    // The located annotation's collapsed row still shows its page.
    expect(screen.getByText('p. 1')).toBeInTheDocument();
    // The active document-scoped annotation carries the whole-document chip.
    expect(screen.getByTestId('whole-document-chip')).toBeInTheDocument();
  });

  it('toggles the active annotation and reveals its thread', () => {
    const props = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });

    expect(screen.getByTestId('thread-a1')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('annotation-item-a1'));
    expect(props.onSelect).toHaveBeenCalledWith(null);
  });

  it('filters by status through the filter popover, with a removable chip', () => {
    renderPanel({
      annotations: [
        annotation('open-1'),
        annotation('resolved-1', { status: AnnotationStatus.Resolved }),
      ],
    });

    fireEvent.click(screen.getByRole('button', { name: 'Filter annotations' }));
    fireEvent.mouseDown(screen.getByLabelText('Status'));
    fireEvent.click(screen.getByRole('option', { name: 'Open' }));

    expect(screen.getByTestId('annotation-item-open-1')).toBeInTheDocument();
    expect(screen.queryByTestId('annotation-item-resolved-1')).not.toBeInTheDocument();

    // The active facet surfaces as a removable chip.
    const chips = within(screen.getByTestId('active-filter-chips'));
    fireEvent.click(
      within(chips.getByText('Open').closest('.MuiChip-root') as HTMLElement).getByTestId(
        'CancelIcon',
      ),
    );
    expect(screen.getByTestId('annotation-item-resolved-1')).toBeInTheDocument();
  });

  it('narrows by full-text search over quote, opening text and author', () => {
    renderPanel({
      annotations: [
        annotation('a-quote'),
        annotation('a-other', {
          anchor: {
            region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
            textQuote: { quote: 'a completely different passage' },
          },
          firstComment: 'unrelated opener',
        }),
      ],
    });

    fireEvent.change(screen.getByLabelText('Search annotations'), {
      target: { value: 'quoted text' },
    });
    expect(screen.getByTestId('annotation-item-a-quote')).toBeInTheDocument();
    expect(screen.queryByTestId('annotation-item-a-other')).not.toBeInTheDocument();
  });

  it('filters by author using the server-resolved display name (issue #413)', () => {
    renderPanel({
      annotations: [
        annotation('a-mine'),
        annotation('a-foreign', { authorId: 'other', authorDisplayName: 'Anna Weber' }),
      ],
    });

    fireEvent.click(screen.getByRole('button', { name: 'Filter annotations' }));
    fireEvent.mouseDown(screen.getByLabelText('Author'));
    fireEvent.click(screen.getByRole('option', { name: 'Anna Weber' }));

    expect(screen.getByTestId('annotation-item-a-foreign')).toBeInTheDocument();
    expect(screen.queryByTestId('annotation-item-a-mine')).not.toBeInTheDocument();
  });

  it('drops the author facet entirely in an anonymous review (issue #413)', () => {
    renderPanel({
      anonymous: true,
      annotations: [
        annotation('a-mine'),
        annotation('a-foreign', { authorId: 'other', authorDisplayName: 'Participant 1' }),
      ],
    });

    fireEvent.click(screen.getByRole('button', { name: 'Filter annotations' }));
    expect(screen.queryByLabelText('Author')).not.toBeInTheDocument();
  });

  it('collapses a section on click', () => {
    renderPanel({ annotations: [annotation('a1')] });

    const header = screen.getByRole('button', { name: /On this version/ });
    expect(header).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('annotation-item-a1')).toBeVisible();

    fireEvent.click(header);
    expect(header).toHaveAttribute('aria-expanded', 'false');
  });

  it('passes the chosen classification to onCreate (issue #403)', () => {
    const props = renderPanel({
      pendingAnchor: {
        region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.1 } },
      },
    });
    const composer = within(screen.getByTestId('annotation-composer'));
    fireEvent.change(composer.getByLabelText('Annotation comment'), {
      target: { value: 'Conflicts with policy' },
    });
    fireEvent.mouseDown(composer.getAllByRole('combobox')[0]);
    fireEvent.click(screen.getByRole('option', { name: /Conflict/ }));
    fireEvent.mouseDown(composer.getAllByRole('combobox')[1]);
    fireEvent.click(screen.getByRole('option', { name: /High/ }));
    fireEvent.click(composer.getByRole('button', { name: /Create annotation/ }));
    expect(props.onCreate).toHaveBeenCalledWith('Conflicts with policy', 'CONFLICT', 'HIGH');
  });

  it('opens the composer for a pending anchor and creates with the comment', () => {
    const props = renderPanel({
      pendingAnchor: {
        region: { surfaceIndex: 1, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.1 } },
      },
    });

    const composer = within(screen.getByTestId('annotation-composer'));
    expect(composer.getByText('Region on page 2')).toBeInTheDocument();

    fireEvent.change(composer.getByLabelText('Annotation comment'), {
      target: { value: 'Wrong figure' },
    });
    fireEvent.click(composer.getByRole('button', { name: /Create annotation/ }));
    expect(props.onCreate).toHaveBeenCalledWith('Wrong figure', undefined, undefined);

    fireEvent.click(composer.getByRole('button', { name: 'Cancel' }));
    expect(props.onCancelPending).toHaveBeenCalled();
  });

  it('requires a non-blank comment before an annotation can be created', () => {
    const props = renderPanel({
      pendingAnchor: {
        region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.1 } },
      },
    });

    const composer = within(screen.getByTestId('annotation-composer'));
    const field = composer.getByLabelText('Annotation comment');
    const create = composer.getByRole('button', { name: /Create annotation/ });

    // Empty and whitespace-only comments keep creating disabled (issue #301) —
    // the button as well as the submit shortcut.
    expect(create).toBeDisabled();
    fireEvent.keyDown(field, { key: 'Enter', metaKey: true });
    fireEvent.change(field, { target: { value: '   ' } });
    expect(create).toBeDisabled();
    fireEvent.keyDown(field, { key: 'Enter', metaKey: true });
    expect(props.onCreate).not.toHaveBeenCalled();

    fireEvent.change(field, { target: { value: 'Needs a source' } });
    expect(create).toBeEnabled();
    fireEvent.click(create);
    expect(props.onCreate).toHaveBeenCalledWith('Needs a source', undefined, undefined);
  });

  it('offers Resolve to the author on their open active annotation (#405)', () => {
    useAuthStore.setState({ userId: 'u1' });
    renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });

    const bar = within(screen.getByTestId('resolve-bar'));
    fireEvent.change(bar.getByLabelText('Optional closing note'), {
      target: { value: 'Fixed in v2.' },
    });
    fireEvent.click(bar.getByText('Resolve'));

    expect(resolveMutate).toHaveBeenCalledWith(
      { annotationId: 'a1', note: 'Fixed in v2.' },
      expect.anything(),
    );
  });

  it("hides the resolve bar from the owner — resolving is the author's call (#405)", () => {
    useAuthStore.setState({ userId: 'owner-1' });
    renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });

    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
  });

  it('hides the resolve bar from uninvolved participants and on resolved annotations', () => {
    useAuthStore.setState({ userId: 'stranger' });
    renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });
    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
    cleanup();

    useAuthStore.setState({ userId: 'u1' });
    renderPanel({
      annotations: [annotation('a2', { status: AnnotationStatus.Resolved })],
      activeAnnotationId: 'a2',
    });
    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
    // The page-level wiring the thread relies on (#403): a resolved
    // annotation's thread is marked closed — and its author may reopen it
    // while the review is still running (#394).
    expect(screen.getByTestId('thread-a2')).toHaveAttribute('data-closed', 'true');
    expect(screen.getByTestId('thread-a2')).toHaveAttribute('data-can-reopen', 'true');
    cleanup();

    // Not the author -> no reopen.
    useAuthStore.setState({ userId: 'stranger' });
    renderPanel({
      annotations: [annotation('a3', { status: AnnotationStatus.Resolved })],
      activeAnnotationId: 'a3',
    });
    expect(screen.getByTestId('thread-a3')).toHaveAttribute('data-can-reopen', 'false');
    cleanup();

    // Finalized review -> resolved annotations stay closed.
    useAuthStore.setState({ userId: 'u1' });
    renderPanel({
      annotations: [annotation('a4', { status: AnnotationStatus.Resolved })],
      activeAnnotationId: 'a4',
      reviewClosed: true,
    });
    expect(screen.getByTestId('thread-a4')).toHaveAttribute('data-can-reopen', 'false');
  });

  it("keeps an open annotation's thread writable", () => {
    useAuthStore.setState({ userId: 'u1' });
    renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });
    expect(screen.getByTestId('thread-a1')).toHaveAttribute('data-closed', 'false');
  });

  it('creates via the submit shortcut and shows the hint', () => {
    const props = renderPanel({
      pendingAnchor: {
        region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.1 } },
      },
    });

    const composer = within(screen.getByTestId('annotation-composer'));
    expect(composer.getByRole('button', { name: /Create annotation \(.+\)/ })).toBeInTheDocument();

    const field = composer.getByLabelText('Annotation comment');
    fireEvent.change(field, { target: { value: 'Shortcut comment' } });
    fireEvent.keyDown(field, { key: 'Enter', metaKey: true });
    expect(props.onCreate).toHaveBeenCalledWith('Shortcut comment', undefined, undefined);

    // Plain Enter stays a newline, Alt+Enter submits too.
    fireEvent.keyDown(field, { key: 'Enter' });
    expect(props.onCreate).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(field, { key: 'Enter', altKey: true });
    expect(props.onCreate).toHaveBeenCalledTimes(2);
  });

  it('suppresses resolving on a read-only (older) version (#306)', () => {
    useAuthStore.setState({ userId: 'u1' });
    renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1', readOnly: true });
    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('thread-a1')).toBeInTheDocument();
  });

  // Issue #412: the annotation copy-link appears on the expanded card when a
  // permalink builder is wired, and stays absent without one.
  it('offers a copy-link affordance on the active annotation with a permalink builder', () => {
    renderPanel({
      annotations: [annotation('a1')],
      activeAnnotationId: 'a1',
      buildPermalink: (id) => `https://qnop.example/reviews/d?annotation=${id}`,
    });
    expect(screen.getByRole('button', { name: 'Copy link to annotation' })).toBeInTheDocument();
  });

  it('omits the copy-link affordance without a permalink builder', () => {
    renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });
    expect(
      screen.queryByRole('button', { name: 'Copy link to annotation' }),
    ).not.toBeInTheDocument();
  });
});
