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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { FocusAnnotationCard } from './FocusAnnotationCard';

vi.mock('../panel/CommentThread', () => ({
  CommentThread: ({ annotationId }: { annotationId: string }) => (
    <div data-testid={`thread-${annotationId}`}>
      <textarea aria-label="Reply" />
    </div>
  ),
}));

const { decideMutate } = vi.hoisted(() => ({ decideMutate: vi.fn() }));
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useDecideAnnotation: () => ({ mutate: decideMutate, isPending: false }),
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
  decideMutate.mockClear();
});

function renderCard(overrides: Partial<Parameters<typeof FocusAnnotationCard>[0]> = {}) {
  const props: Parameters<typeof FocusAnnotationCard>[0] = {
    annotation: ANNOTATION,
    anchorEl: anchor,
    position: { index: 1, count: 3, prevId: 'a1', nextId: 'a3' },
    onNavigate: vi.fn(),
    onClose: vi.fn(),
    ownerId: 'owner-1',
    userId: 'author-1',
    notify: vi.fn(),
    ...overrides,
  };
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <FocusAnnotationCard {...props} />
    </ThemeProvider>,
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
    // The author may decide their own annotation (ADR-0011).
    expect(screen.getByTestId('decision-bar')).toBeInTheDocument();
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
    expect(style.maxHeight).toContain('72vh');
  });

  it('disables the ends of the walk and hides deciding from uninvolved users', () => {
    renderCard({
      position: { index: 0, count: 3, prevId: null, nextId: 'a3' },
      userId: 'stranger',
    });
    expect(screen.getByRole('button', { name: 'Previous annotation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next annotation' })).toBeEnabled();
    expect(screen.queryByTestId('decision-bar')).not.toBeInTheDocument();
  });
});
