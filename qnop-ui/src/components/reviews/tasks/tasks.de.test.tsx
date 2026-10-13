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
import { cleanup, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import {
  AnnotationPriority,
  AnnotationStatus,
  AnnotationType,
  PlacementStatus,
} from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { TaskBoard } from './TaskBoard';
import { TaskListRows } from './TaskListRows';

vi.mock('../markdown/useMentionNames', () => ({
  useMentionNames: () => (text: string) => text,
}));

const annotation = (id: string, overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'u1',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  anchor: {
    region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
    textQuote: { quote: 'die Zahlungsfrist' },
  },
  type: AnnotationType.Conflict,
  priority: AnnotationPriority.High,
  firstComment: `Kommentar ${id}`,
  commentCount: 1,
  reactions: [],
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
  ...overrides,
});

const theme = buildTheme('light', 'de');

/** The tasks views in German (issue #836): column language, cues and fallbacks. */
describe('tasks in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('labels the board columns, counts and cards in German', () => {
    render(
      <ThemeProvider theme={theme}>
        <TaskBoard
          annotations={[
            annotation('a-open', { firstComment: undefined, anchor: undefined }),
            annotation('a-talk', { commentCount: 3, type: AnnotationType.Proposal }),
          ]}
          taskKeyOf={() => 'T-1'}
          authorNameOf={() => 'Maxim'}
          mayResolve={() => true}
          onOpen={vi.fn()}
          onResolve={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(screen.getByText('Offen (1)')).toBeInTheDocument();
    expect(screen.getByText('In Diskussion (1)')).toBeInTheDocument();
    expect(screen.getByText('Erledigt (0)')).toBeInTheDocument();
    expect(screen.getByText('Leg deine Karte hier ab, um sie zu erledigen')).toBeInTheDocument();
    // A document-scoped card without an opener falls back to the generic title.
    expect(screen.getByText('Anmerkung')).toBeInTheDocument();
    expect(screen.getByText('Konflikt')).toBeInTheDocument();
    expect(screen.getByText('Vorschlag')).toBeInTheDocument();
    expect(screen.getByText('„die Zahlungsfrist“')).toBeInTheDocument();
    expect(screen.getByLabelText('3 Kommentare')).toBeInTheDocument();
    expect(screen.getByLabelText('1 Kommentar')).toBeInTheDocument();
  });

  it('speaks the list rows in German', () => {
    render(
      <ThemeProvider theme={theme}>
        <TaskListRows
          annotations={[
            annotation('a-talk', { commentCount: 2 }),
            annotation('a-done', { status: AnnotationStatus.Resolved, type: undefined }),
            annotation('a-doc', { anchor: undefined }),
          ]}
          taskKeyOf={() => 'T-1'}
          authorNameOf={() => 'Maxim'}
          onOpen={vi.fn()}
        />
      </ThemeProvider>,
    );

    const talk = within(screen.getByTestId('task-row-a-talk'));
    expect(talk.getByText('In Diskussion')).toBeInTheDocument();
    expect(talk.getByText('„die Zahlungsfrist“ · S. 1 · Maxim')).toBeInTheDocument();
    expect(within(screen.getByTestId('task-row-a-done')).getByText('Erledigt')).toBeVisible();
    expect(
      within(screen.getByTestId('task-row-a-doc')).getByText('Ganzes Dokument · Maxim'),
    ).toBeInTheDocument();
  });

  it('says so when nothing matches', () => {
    render(
      <ThemeProvider theme={theme}>
        <TaskListRows
          annotations={[]}
          taskKeyOf={() => ''}
          authorNameOf={() => ''}
          onOpen={vi.fn()}
        />
      </ThemeProvider>,
    );
    expect(screen.getByText('Keine Anmerkungen passen zum aktuellen Filter.')).toBeInTheDocument();
  });
});
