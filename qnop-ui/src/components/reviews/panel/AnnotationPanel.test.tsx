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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { AnnotationPanel } from './AnnotationPanel';

vi.mock('./CommentThread', () => ({
  CommentThread: ({ annotationId }: { annotationId: string }) => (
    <div data-testid={`thread-${annotationId}`} />
  ),
}));

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
  it('shows the empty state with the how-to hint when annotating is possible', () => {
    renderPanel();
    expect(
      screen.getByText(/No annotations yet\. Select text or draw a region/),
    ).toBeInTheDocument();
  });

  it('lists placed annotations and separates unplaced ones', () => {
    renderPanel({
      annotations: [
        annotation('placed-1'),
        annotation('orphaned-1', {
          anchor: undefined,
          placementStatus: PlacementStatus.Orphaned,
        }),
      ],
    });

    expect(screen.getByText('Annotations (2)')).toBeInTheDocument();
    expect(screen.getByText('Not placed on this version')).toBeInTheDocument();
    expect(screen.getByText('Orphaned')).toBeInTheDocument();
    expect(screen.getByText('“quoted text”')).toBeInTheDocument();
  });

  it('toggles the active annotation and reveals its thread', () => {
    const props = renderPanel({ annotations: [annotation('a1')], activeAnnotationId: 'a1' });

    expect(screen.getByTestId('thread-a1')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('annotation-item-a1'));
    expect(props.onSelect).toHaveBeenCalledWith(null);
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
    fireEvent.click(composer.getByRole('button', { name: 'Create annotation' }));
    expect(props.onCreate).toHaveBeenCalledWith('Wrong figure');

    fireEvent.click(composer.getByRole('button', { name: 'Cancel' }));
    expect(props.onCancelPending).toHaveBeenCalled();
  });
});
