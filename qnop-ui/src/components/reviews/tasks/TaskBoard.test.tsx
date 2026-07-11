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
import type { Mock } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import {
  AnnotationPriority,
  AnnotationStatus,
  AnnotationType,
  PlacementStatus,
} from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { TaskBoard } from './TaskBoard';
import { TASK_DRAG_TYPE } from './TaskCard';

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
  type: AnnotationType.Conflict,
  priority: AnnotationPriority.High,
  firstComment: `first comment of ${id}`,
  commentCount: 1,
  reactions: [],
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

const ANNOTATIONS = [
  annotation('a-open'),
  annotation('a-talk', { commentCount: 3 }),
  annotation('a-done', { status: AnnotationStatus.Resolved }),
];

function renderBoard({
  mayResolve = () => true,
  onResolve = vi.fn<(annotationId: string) => void>(),
  onOpen = vi.fn<(annotationId: string) => void>(),
}: {
  mayResolve?: (annotation: AnnotationView) => boolean;
  onResolve?: Mock<(annotationId: string) => void>;
  onOpen?: Mock<(annotationId: string) => void>;
} = {}) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <TaskBoard
        annotations={ANNOTATIONS}
        taskKeyOf={() => 'T-1'}
        authorNameOf={() => 'Maxim'}
        mayResolve={mayResolve}
        onOpen={onOpen}
        onResolve={onResolve}
      />
    </ThemeProvider>,
  );
  return { onResolve, onOpen };
}

const dataTransfer = (id: string) => ({
  types: [TASK_DRAG_TYPE],
  getData: (key: string) => (key === TASK_DRAG_TYPE ? id : ''),
  setData: vi.fn(),
  dropEffect: 'move',
});

describe('TaskBoard', () => {
  it('sorts annotations into open, derived discussion and resolved columns', () => {
    renderBoard();
    expect(
      within(screen.getByTestId('task-column-open')).getByTestId('task-card-a-open'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId('task-column-discussion')).getByTestId('task-card-a-talk'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId('task-column-done')).getByTestId('task-card-a-done'),
    ).toBeInTheDocument();
  });

  it('opens a task on click', () => {
    const { onOpen } = renderBoard();
    fireEvent.click(screen.getByTestId('task-card-a-open'));
    expect(onOpen).toHaveBeenCalledWith('a-open');
  });

  it('resolves a card dropped on the Resolved column', () => {
    const { onResolve } = renderBoard();
    fireEvent.drop(screen.getByTestId('task-column-done'), {
      dataTransfer: dataTransfer('a-open'),
    });
    expect(onResolve).toHaveBeenCalledWith('a-open');
  });

  it('marks foreign cards created after the previous visit', () => {
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <TaskBoard
          annotations={[annotation('a-new', { authorId: 'someone-else' })]}
          previousSeenAt="2026-06-30T00:00:00Z"
          taskKeyOf={() => 'T-1'}
          authorNameOf={() => 'Maxim'}
          mayResolve={() => true}
          onOpen={vi.fn()}
          onResolve={vi.fn()}
        />
      </ThemeProvider>,
    );
    expect(
      within(screen.getByTestId('task-card-a-new')).getByTestId('unseen-dot'),
    ).toBeInTheDocument();
  });

  it("only the author's own cards are draggable; resolved cards never are", () => {
    renderBoard({ mayResolve: (a) => a.id === 'a-open' });
    expect(screen.getByTestId('task-card-a-open')).toHaveAttribute('draggable', 'true');
    expect(screen.getByTestId('task-card-a-talk')).toHaveAttribute('draggable', 'false');
    expect(screen.getByTestId('task-card-a-done')).toHaveAttribute('draggable', 'false');
  });
});
