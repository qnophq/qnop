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
import { axe } from 'vitest-axe';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, ParticipantKind, PlacementStatus } from '../../../api/generated';
import { useParticipants } from '../../../api/hooks/useReviews';
import { useDocument } from '../../../api/hooks/useDocuments';
import { buildTheme } from '../../../theme/theme';
import { useAuthStore } from '../../../stores/authStore';
import { AnnotationPanel } from './AnnotationPanel';

// The reaction toggles (issue #410) reach for the query client; the data
// hooks above stay mocked, so a bare client per file is all the tests need.
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

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

// Partial: only the review lookup is steered. It gates the profile hover cards
// (issue #482) — without a review they render neither card nor link, and the
// nested-interactive assertions below would be vacuous.
vi.mock('../../../api/hooks/useDocuments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/hooks/useDocuments')>()),
  useDocument: vi.fn(),
}));

vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: false, isError: false, data: undefined }),
}));

const { resolveMutate, confirmMutate } = vi.hoisted(() => ({
  resolveMutate: vi.fn(),
  confirmMutate: vi.fn(),
}));
vi.mock('../../../api/hooks/useReviews', () => ({
  useParticipants: vi.fn(),
}));
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useConfirmPlacement: vi.fn().mockReturnValue({ mutate: confirmMutate, isPending: false }),
  useReattachPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useResolveAnnotation: () => ({ mutate: resolveMutate, isPending: false }),
  useReopenAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
  useDismissAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
}));

beforeEach(() => {
  resolveMutate.mockClear();
  confirmMutate.mockClear();
  vi.mocked(useDocument).mockReturnValue({ data: undefined } as never);
  vi.mocked(useParticipants).mockReturnValue({
    data: { participants: [{ principalId: 'other', displayName: 'Anna Weber' }] },
  } as never);
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
  reactions: [],
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
  const wrap = (current: Parameters<typeof AnnotationPanel>[0]) => (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        {/* The profile links in the hover cards (issue #482) are router links. */}
        <MemoryRouter>
          <AnnotationPanel {...current} />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
  const { rerender, container } = render(wrap(merged));
  return {
    ...merged,
    container,
    /** Re-renders the same panel with patched props — the panel keeps its own state. */
    update: (patch: Partial<Parameters<typeof AnnotationPanel>[0]>) =>
      rerender(wrap({ ...merged, ...patch })),
  };
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
    expect(screen.getByTestId('panel-new-count')).toHaveTextContent('2 new');
  });

  it('scrolls the selected row into view when a mark click activates it (#491)', () => {
    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;

    renderPanel({
      annotations: [annotation('a1'), annotation('a2')],
      activeAnnotationId: 'a2',
    });

    expect(scrollSpy).toHaveBeenCalledWith({ block: 'nearest' });
    expect(scrollSpy.mock.instances[0]).toBe(screen.getByTestId('annotation-item-a2'));
  });

  it('shows the empty state with the how-to hint when annotating is possible', () => {
    renderPanel();
    expect(
      screen.getByText(/No annotations yet\. Select text or draw a region/),
    ).toBeInTheDocument();
  });

  it('renders one flat list; document-scoped cards carry their own marker (#481)', () => {
    renderPanel({
      annotations: [
        annotation('placed-1'),
        // A document-scoped annotation: no anchor and no placement (issue #395).
        annotation('doc-1', { anchor: undefined, placementStatus: undefined }),
      ],
      activeAnnotationId: 'doc-1',
    });

    expect(screen.getByText('Annotations (2)')).toBeInTheDocument();
    // No section chrome anymore — the scope reads per card (issue #481).
    expect(screen.queryByText('General remarks — not pinned to a passage')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Anchored to the document/ }),
    ).not.toBeInTheDocument();
    // The located annotation's collapsed row still shows its page.
    expect(screen.getByText('p. 1')).toBeInTheDocument();
    // The active document-scoped annotation carries the whole-document chip.
    expect(screen.getByTestId('whole-document-chip')).toBeInTheDocument();
  });

  it('offers a Global annotation action for a whole-document remark when writable (#395)', () => {
    const onNewDocumentNote = vi.fn();
    renderPanel({ onNewDocumentNote });

    fireEvent.click(screen.getByRole('button', { name: 'Global annotation' }));
    expect(onNewDocumentNote).toHaveBeenCalledTimes(1);
  });

  it('hides Global annotation on a read-only or closed review, or without a handler (#395)', () => {
    renderPanel({ onNewDocumentNote: vi.fn(), readOnly: true });
    expect(screen.queryByRole('button', { name: 'Global annotation' })).not.toBeInTheDocument();
    cleanup();

    renderPanel({ onNewDocumentNote: vi.fn(), reviewClosed: true });
    expect(screen.queryByRole('button', { name: 'Global annotation' })).not.toBeInTheDocument();
    cleanup();

    renderPanel({});
    expect(screen.queryByRole('button', { name: 'Global annotation' })).not.toBeInTheDocument();
  });

  it('toggles the active annotation and reveals its thread', () => {
    const props = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });

    expect(screen.getByTestId('thread-a1')).toBeInTheDocument();
    // Expanded, the card is a plain container and collapsing is an explicit
    // control rather than a click anywhere on it (issue #549).
    fireEvent.click(screen.getByTestId('annotation-toggle-a1'));
    expect(props.onSelect).toHaveBeenCalledWith(null);
  });

  it('expands a collapsed row from its toggle', () => {
    const props = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: null });

    fireEvent.click(screen.getByTestId('annotation-toggle-a1'));
    expect(props.onSelect).toHaveBeenCalledWith('a1');
  });

  // Issue #480: acting on a placement must never toggle the card the reviewer
  // is reading. Since #549 the expanded card is no longer a button at all, so
  // this holds structurally rather than through a click guard.
  it('keeps the row expanded when "Looks right" confirms a MOVED placement (#480)', () => {
    useAuthStore.setState({ userId: 'u1' });
    const props = renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Moved })],
      activeAnnotationId: 'a1',
      versionNumber: 3,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Looks right' }));

    expect(confirmMutate).toHaveBeenCalledWith({
      annotationId: 'a1',
      versionNumber: 3,
    });
    expect(props.onSelect).not.toHaveBeenCalled();
  });

  // Issue #548: reaching an annotation through the banner narrows the list to
  // "needs attention" — confirming the placement then settles it out of that
  // facet, and the expanded card the reviewer is reading must not vanish.
  it('keeps the active annotation listed after "Looks right" settles it out of the attention facet (#548)', () => {
    useAuthStore.setState({ userId: 'u1' });
    const panel = renderPanel({
      annotations: [
        annotation('a1', { placementStatus: PlacementStatus.Moved }),
        annotation('a2', { placementStatus: PlacementStatus.Orphaned }),
      ],
      activeAnnotationId: null,
      versionNumber: 3,
    });

    // The banner's "Review" action sets the placement facet.
    fireEvent.click(
      within(screen.getByTestId('reanchor-banner')).getByRole('button', {
        name: 'Review',
      }),
    );
    expect(screen.getByTestId('annotation-item-a1')).toBeInTheDocument();

    // The reviewer opens the moved annotation and confirms its placement; the
    // refetch flips it MOVED → PLACED, which no longer matches "attention".
    panel.update({ activeAnnotationId: 'a1' });
    fireEvent.click(screen.getByRole('button', { name: 'Looks right' }));
    panel.update({
      activeAnnotationId: 'a1',
      annotations: [
        annotation('a1', { placementStatus: PlacementStatus.Placed }),
        annotation('a2', { placementStatus: PlacementStatus.Orphaned }),
      ],
    });

    // Still listed, still expanded — and the untouched orphan still matches.
    expect(screen.getByTestId('annotation-item-a1')).toBeInTheDocument();
    expect(screen.getByTestId('thread-a1')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-item-a2')).toBeInTheDocument();
  });

  it('explains the empty list when the pinned annotation is all that is left (#548)', () => {
    useAuthStore.setState({ userId: 'u1' });
    const panel = renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Moved })],
      activeAnnotationId: null,
      versionNumber: 3,
    });

    // Filter first, then open the annotation — the order the banner produces.
    fireEvent.click(
      within(screen.getByTestId('reanchor-banner')).getByRole('button', { name: 'Review' }),
    );
    panel.update({
      activeAnnotationId: 'a1',
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Moved })],
    });
    panel.update({
      activeAnnotationId: 'a1',
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Placed })],
    });

    expect(screen.getByTestId('annotation-item-a1')).toBeInTheDocument();
    expect(screen.getByText(/the one you have open stays until you select another/)).toBeVisible();
  });

  it('still drops the active annotation when the reviewer re-filters themselves (#548)', () => {
    renderPanel({
      annotations: [
        annotation('a1', { placementStatus: PlacementStatus.Placed }),
        annotation('a2', { placementStatus: PlacementStatus.Orphaned }),
      ],
      activeAnnotationId: 'a1',
    });

    fireEvent.click(screen.getByRole('button', { name: 'Filter annotations' }));
    fireEvent.mouseDown(screen.getByLabelText('Placement'));
    fireEvent.click(screen.getByRole('option', { name: 'Needs attention' }));

    expect(screen.queryByTestId('annotation-item-a1')).not.toBeInTheDocument();
    expect(screen.getByTestId('annotation-item-a2')).toBeInTheDocument();
  });

  // Issue #562: a healthy placement offers Re-position to the author under the
  // operator switch, and to admins regardless of it.
  it('offers Re-position on PLACED to the author when free re-attach is enabled (#562)', () => {
    useAuthStore.setState({ userId: 'u1' });
    const onArmReattach = vi.fn();
    renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Placed })],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      onArmReattach,
      freeReattachEnabled: true,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Re-position' }));
    expect(onArmReattach).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
  });

  it('hides Re-position from the author while the switch is off, but admins always see it (#562)', () => {
    useAuthStore.setState({ userId: 'u1' });
    renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Placed })],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      onArmReattach: vi.fn(),
    });
    expect(screen.queryByRole('button', { name: 'Re-position' })).toBeNull();

    cleanup();
    useAuthStore.setState({ userId: 'someone-else' });
    renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Placed })],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      onArmReattach: vi.fn(),
      viewerIsAdmin: true,
    });
    expect(screen.getByRole('button', { name: 'Re-position' })).toBeInTheDocument();
  });

  // Issue #479: MOVED offers Re-attach alongside Looks right; #480's
  // no-collapse guarantee must hold for it too.
  it('arms re-attach on a MOVED placement without collapsing the row (#479)', () => {
    useAuthStore.setState({ userId: 'u1' });
    const onArmReattach = vi.fn();
    const props = renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Moved })],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      onArmReattach,
    });

    expect(screen.getByRole('button', { name: 'Looks right' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Re-attach' }));

    expect(onArmReattach).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
    expect(props.onSelect).not.toHaveBeenCalled();
  });

  it('keeps the row expanded when "Re-attach" arms re-attach mode (#480)', () => {
    useAuthStore.setState({ userId: 'u1' });
    const onArmReattach = vi.fn();
    const props = renderPanel({
      annotations: [annotation('a1', { placementStatus: PlacementStatus.Orphaned })],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      onArmReattach,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Re-attach' }));

    expect(onArmReattach).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
    expect(props.onSelect).not.toHaveBeenCalled();
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

  it('shows mention tokens as display names in the collapsed excerpt (#462)', () => {
    vi.mocked(useParticipants).mockReturnValue({
      data: {
        participants: [
          {
            principalId: 'u9',
            displayName: 'Ben Roth',
            slug: 'ben-roth',
            kind: ParticipantKind.User,
          },
        ],
      },
    } as never);
    renderPanel({
      annotations: [
        annotation('a-doc', { anchor: undefined, firstComment: 'ping @ben-roth please' }),
      ],
    });

    const row = screen.getByTestId('annotation-item-a-doc');
    expect(row).toHaveTextContent('ping Ben Roth please');
    expect(row).not.toHaveTextContent('@ben-roth');
  });

  it('finds an annotation by the name its mention resolves to (#462)', () => {
    vi.mocked(useParticipants).mockReturnValue({
      data: {
        participants: [
          {
            principalId: 'u9',
            displayName: 'Ben Roth',
            slug: 'ben-roth',
            kind: ParticipantKind.User,
          },
        ],
      },
    } as never);
    renderPanel({
      annotations: [
        annotation('a-mention', { anchor: undefined, firstComment: 'ping @ben-roth please' }),
        annotation('a-other', { anchor: undefined, firstComment: 'something else' }),
      ],
    });

    fireEvent.change(screen.getByLabelText('Search annotations'), {
      target: { value: 'Ben Roth' },
    });
    expect(screen.getByTestId('annotation-item-a-mention')).toBeInTheDocument();
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

  it('orders the flat list: document-scoped first, then anchored by position (#481)', () => {
    renderPanel({
      annotations: [
        annotation('page-2', {
          anchor: { region: { surfaceIndex: 1, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.1 } } },
        }),
        annotation('page-1'),
        annotation('doc-note', { anchor: undefined, placementStatus: undefined }),
      ],
    });

    const ids = screen
      .getAllByTestId(/annotation-item-/)
      .map((el) => el.getAttribute('data-testid'));
    expect(ids).toEqual([
      'annotation-item-doc-note',
      'annotation-item-page-1',
      'annotation-item-page-2',
    ]);
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

  // Issue #462 follow-up: mentioning must work when CREATING an annotation,
  // not only in the comment threads — the roster reaches the composer.
  it('offers the @-mention roster in the annotation composer', () => {
    renderPanel({
      pendingAnchor: annotation('a1').anchor!,
      mentionCandidates: [
        { id: '018f5a3e-0000-7000-8000-000000000001', name: 'Alice', slug: 'alice-smith' },
      ],
    });

    const ta = screen.getByLabelText('Annotation comment') as HTMLTextAreaElement;
    ta.focus();
    fireEvent.change(ta, { target: { value: '@Al' } });
    ta.setSelectionRange(3, 3);
    fireEvent.keyUp(ta, { key: 'l' });

    expect(screen.getByText('Alice')).toBeInTheDocument();
  });
});

// Issue #549: the expanded card hosts real controls — placement actions, copy,
// reactions, profile links — so it may not be a role="button" itself. The
// toggle is explicit instead: the collapsed row IS the button, the expanded
// card carries a collapse chevron.
describe('AnnotationPanel accessibility (#549)', () => {
  beforeEach(() => {
    // A non-anonymous review: every author id is real, so the hover cards and
    // their profile links render (issue #482).
    vi.mocked(useDocument).mockReturnValue({
      data: { id: 'd1', anonymous: false, ownerId: 'u1' },
    } as never);
  });

  /** Every focusable element that sits inside something announced as a button. */
  const nestedInteractives = (container: HTMLElement) =>
    [...container.querySelectorAll('button, [role="button"]')].flatMap((widget) => [
      ...widget.querySelectorAll('a[href], button, input, select, textarea, [tabindex]'),
    ]);

  const AXE_OPTIONS = { rules: { region: { enabled: false } } };

  /** An annotation with every expanded-state control in play. */
  const loaded = () =>
    annotation('a1', {
      placementStatus: PlacementStatus.Moved,
      reactions: [{ emoji: '👍', count: 1, reactedByMe: false, reactors: ['Anna Weber'] }],
    });

  it('nests no interactive control inside a button, collapsed or expanded', () => {
    useAuthStore.setState({ userId: 'u1' });
    const collapsed = renderPanel({ annotations: [loaded()], activeAnnotationId: null });
    expect(nestedInteractives(collapsed.container)).toEqual([]);
    cleanup();

    const expanded = renderPanel({
      annotations: [loaded()],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      buildPermalink: () => 'https://qnop.test/reviews/d1?annotation=a1',
      onArmReattach: vi.fn(),
    });
    // The card really does carry the controls this test is about.
    expect(screen.getByRole('button', { name: 'Looks right' })).toBeInTheDocument();
    expect(nestedInteractives(expanded.container)).toEqual([]);
  });

  it('has no axe violations with a collapsed list', async () => {
    useAuthStore.setState({ userId: 'u1' });
    const { container } = renderPanel({
      annotations: [loaded(), annotation('a2')],
      activeAnnotationId: null,
    });

    expect(await axe(container, AXE_OPTIONS)).toHaveNoViolations();
  });

  it('has no axe violations with the card expanded', async () => {
    useAuthStore.setState({ userId: 'u1' });
    const { container } = renderPanel({
      annotations: [loaded()],
      activeAnnotationId: 'a1',
      versionNumber: 3,
      buildPermalink: () => 'https://qnop.test/reviews/d1?annotation=a1',
      onArmReattach: vi.fn(),
    });

    expect(await axe(container, AXE_OPTIONS)).toHaveNoViolations();
  });

  it('announces the toggle state on the control that carries it', () => {
    const panel = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: null });
    expect(screen.getByTestId('annotation-toggle-a1')).toHaveAttribute('aria-expanded', 'false');

    panel.update({ activeAnnotationId: 'a1' });
    expect(screen.getByTestId('annotation-toggle-a1')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('annotation-toggle-a1')).toHaveAccessibleName('Collapse annotation');
  });

  // Expanding replaces the focused row button with the collapse chevron, so
  // without this the keyboard user is dropped back to the document body.
  it('carries focus across the toggle in both directions', () => {
    const panel = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: null });

    const row = screen.getByTestId('annotation-toggle-a1');
    row.focus();
    fireEvent.click(row);
    panel.update({ activeAnnotationId: 'a1' });
    expect(screen.getByTestId('annotation-toggle-a1')).toHaveFocus();

    fireEvent.click(screen.getByTestId('annotation-toggle-a1'));
    panel.update({ activeAnnotationId: null });
    expect(screen.getByTestId('annotation-toggle-a1')).toHaveFocus();
  });

  // A mark click on the page selects the row too (#491) — focus belongs to the
  // document there, not to the panel.
  it('leaves focus alone when the selection arrives from outside', () => {
    const panel = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: null });

    panel.update({ activeAnnotationId: 'a1' });

    expect(screen.getByTestId('annotation-toggle-a1')).not.toHaveFocus();
  });
});
