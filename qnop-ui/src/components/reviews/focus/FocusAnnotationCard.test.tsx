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
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { FocusAnnotationCard } from './FocusAnnotationCard';

// The reaction toggles (issue #410) reach for the query client; the data
// hooks above stay mocked, so a bare client per file is all the tests need.
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('../panel/CommentThread', () => ({
  CommentThread: ({ annotationId }: { annotationId: string }) => (
    <div data-testid={`thread-${annotationId}`}>
      <textarea aria-label="Reply" />
    </div>
  ),
}));

vi.mock('../../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: false, isError: false, data: undefined }),
}));

const { resolveMutate } = vi.hoisted(() => ({ resolveMutate: vi.fn() }));
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useConfirmPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useReattachPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useResolveAnnotation: () => ({ mutate: resolveMutate, isPending: false }),
  useReopenAnnotation: () => ({ mutate: vi.fn(), isPending: false }),
}));

const ANNOTATION: AnnotationView = {
  id: 'a2',
  documentId: 'd1',
  authorId: 'author-1',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  anchor: {
    region: { surfaceIndex: 0, box: { x: 0.1, y: 0.2, width: 0.3, height: 0.02 } },
    textQuote: { quote: 'the disputed clause' },
  },
  commentCount: 2,
  reactions: [],
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
};

let anchor: HTMLElement;

beforeEach(() => {
  anchor = document.createElement('button');
  document.body.appendChild(anchor);
});

afterEach(() => {
  anchor.remove();
  resolveMutate.mockClear();
});

function renderCard(overrides: Partial<Parameters<typeof FocusAnnotationCard>[0]> = {}) {
  const props: Parameters<typeof FocusAnnotationCard>[0] = {
    annotation: ANNOTATION,
    anchorEl: anchor,
    position: { index: 1, count: 3, prevId: 'a1', nextId: 'a3' },
    onNavigate: vi.fn(),
    onClose: vi.fn(),
    userId: 'author-1',
    notify: vi.fn(),
    ...overrides,
  };
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <FocusAnnotationCard {...props} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return props;
}

describe('FocusAnnotationCard', () => {
  it('shows the walk counter, quote, cues and the full thread', () => {
    renderCard();
    expect(screen.getByText('Annotation 2 of 3')).toBeInTheDocument();
    expect(screen.getByText('“the disputed clause”')).toBeInTheDocument();
    expect(screen.getByText('Open')).toBeInTheDocument();
    expect(screen.getByTestId('thread-a2')).toBeInTheDocument();
    // Resolving belongs to the author (issue #405) — the default viewer IS
    // the author, so the resolve bar shows and reports the resolve on click.
    const bar = within(screen.getByTestId('resolve-bar'));
    fireEvent.click(bar.getByText('Resolve'));
    expect(resolveMutate).toHaveBeenCalledWith(
      { annotationId: 'a2', note: undefined },
      expect.anything(),
    );
  });

  it("hides the resolve bar from the owner — resolving is the author's call (#405)", () => {
    renderCard({ userId: 'owner-1' });
    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
  });

  it('walks prev/next via the buttons', () => {
    const props = renderCard();
    fireEvent.click(screen.getByRole('button', { name: 'Previous annotation' }));
    expect(props.onNavigate).toHaveBeenCalledWith('a1');
    fireEvent.click(screen.getByRole('button', { name: 'Next annotation' }));
    expect(props.onNavigate).toHaveBeenCalledWith('a3');
  });

  it('walks with the arrow keys and closes on Escape', () => {
    const props = renderCard();
    const card = screen.getByTestId('focus-annotation-card');
    fireEvent.keyDown(card, { key: 'ArrowDown' });
    expect(props.onNavigate).toHaveBeenCalledWith('a3');
    fireEvent.keyDown(card, { key: 'ArrowUp' });
    expect(props.onNavigate).toHaveBeenCalledWith('a1');
    fireEvent.keyDown(card, { key: 'Escape' });
    expect(props.onClose).toHaveBeenCalled();
  });

  it('leaves the arrow keys to the caret inside text fields', () => {
    const props = renderCard();
    fireEvent.keyDown(screen.getByLabelText('Reply'), { key: 'ArrowDown' });
    expect(props.onNavigate).not.toHaveBeenCalled();
  });

  it('is resizable within hard bounds', () => {
    renderCard();
    const body = screen.getByTestId('focus-card-body');
    const style = getComputedStyle(body);
    expect(style.resize).toBe('both');
    expect(style.minWidth).toBe('320px');
    expect(style.minHeight).toBe('220px');
    expect(style.maxWidth).toContain('640px');
    expect(style.maxHeight).toContain('64vh');
  });

  it('drags by the header — buttons excluded — and offsets the card', () => {
    renderCard();
    const handle = screen.getByTestId('focus-card-handle');
    const card = screen.getByTestId('focus-annotation-card');

    fireEvent.pointerDown(handle, { button: 0, clientX: 100, clientY: 100 });
    fireEvent.pointerMove(handle, { clientX: 140, clientY: 70 });
    fireEvent.pointerUp(handle);
    expect(card.style.translate).toBe('40px -30px');

    // A pointer-down on the walk buttons must not start a drag.
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Next annotation' }), {
      button: 0,
      clientX: 0,
      clientY: 0,
    });
    fireEvent.pointerMove(handle, { clientX: 50, clientY: 50 });
    expect(card.style.translate).toBe('40px -30px');
  });

  it('disables the ends of the walk and hides resolving from other reviewers', () => {
    renderCard({
      position: { index: 0, count: 3, prevId: null, nextId: 'a3' },
      userId: 'stranger',
    });
    expect(screen.getByRole('button', { name: 'Previous annotation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next annotation' })).toBeEnabled();
    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
  });

  it('suppresses resolving on a read-only (older) version (#306)', () => {
    renderCard({ readOnly: true });
    expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('thread-a2')).toBeInTheDocument();
  });

  // Issue #412: the shared copy-link affordance rides the card header.
  it('offers a copy-link affordance in the header when a permalink builder is wired', () => {
    renderCard({ buildPermalink: (id) => `https://qnop.example/reviews/d?annotation=${id}` });
    expect(screen.getByRole('button', { name: 'Copy link to annotation' })).toBeInTheDocument();
  });
});
