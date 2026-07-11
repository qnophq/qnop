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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { AnnotationBadgeRow } from './AnnotationBadgeRow';

const annotation = (overrides: Partial<AnnotationView>): AnnotationView =>
  ({
    id: 'a1',
    documentId: 'd1',
    authorId: 'u1',
    status: 'OPEN',
    commentCount: 1,
    reactions: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
    ...overrides,
  }) as AnnotationView;

function renderRow(
  view: AnnotationView,
  handlers: Parameters<typeof AnnotationBadgeRow>[0] = {} as never,
) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <AnnotationBadgeRow annotation={view} {...handlers} />
    </ThemeProvider>,
  );
}

describe('AnnotationBadgeRow placement affordances', () => {
  it('offers Re-attach for an orphaned placement and stops the card click', () => {
    const onReattachPlacement = vi.fn();
    const cardClick = vi.fn();
    render(
      <ThemeProvider theme={buildTheme('light')}>
        {/* Stands in for the clickable annotation card the row sits inside. */}
        <div role="presentation" onClick={cardClick}>
          <AnnotationBadgeRow
            annotation={annotation({ placementStatus: PlacementStatus.Orphaned })}
            onReattachPlacement={onReattachPlacement}
          />
        </div>
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Re-attach' }));
    expect(onReattachPlacement).toHaveBeenCalled();
    expect(cardClick).not.toHaveBeenCalled();
  });

  it('offers Re-attach for a FAILED placement too', () => {
    renderRow(annotation({ placementStatus: PlacementStatus.Failed }), {
      onReattachPlacement: vi.fn(),
    } as never);
    expect(screen.getByRole('button', { name: 'Re-attach' })).toBeInTheDocument();
  });

  it('keeps Re-attach away from settled placements and Looks right on MOVED only', () => {
    renderRow(annotation({ placementStatus: PlacementStatus.Placed }), {
      onReattachPlacement: vi.fn(),
      onConfirmPlacement: vi.fn(),
    } as never);
    expect(screen.queryByRole('button', { name: 'Re-attach' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Looks right' })).not.toBeInTheDocument();
  });

  it('hides Re-attach without a handler — read-only viewers see only the chip', () => {
    renderRow(annotation({ placementStatus: PlacementStatus.Orphaned }));
    expect(screen.queryByRole('button', { name: 'Re-attach' })).not.toBeInTheDocument();
  });
});
