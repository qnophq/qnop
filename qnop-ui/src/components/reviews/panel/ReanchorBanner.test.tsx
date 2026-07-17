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
import type { AnnotationView } from '../../../api/generated';
import { PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { ReanchorBanner } from './ReanchorBanner';

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

function renderBanner(annotations: AnnotationView[], onReview = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <ReanchorBanner annotations={annotations} versionNumber={3} onReview={onReview} />
    </ThemeProvider>,
  );
  return onReview;
}

beforeEach(() => localStorage.clear());

describe('ReanchorBanner (issue #326)', () => {
  it('summarises the version’s re-anchoring outcomes and filters on Review', () => {
    const onReview = renderBanner([
      annotation({ id: 'm1', placementStatus: PlacementStatus.Moved }),
      annotation({ id: 'o1', placementStatus: PlacementStatus.Orphaned }),
      annotation({ id: 'p1', placementStatus: PlacementStatus.Placed }),
    ]);

    expect(screen.getByTestId('reanchor-banner')).toHaveTextContent(
      'Re-anchoring on v3: 1 moved, 1 orphaned.',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(onReview).toHaveBeenCalled();
  });

  it('stays silent when every placement is settled', () => {
    renderBanner([annotation({ id: 'p1', placementStatus: PlacementStatus.Placed })]);
    expect(screen.queryByTestId('reanchor-banner')).not.toBeInTheDocument();
  });

  it('dismisses per document version and stays dismissed', () => {
    renderBanner([annotation({ id: 'm1', placementStatus: PlacementStatus.Moved })]);
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss re-anchoring notice' }));
    expect(screen.queryByTestId('reanchor-banner')).not.toBeInTheDocument();

    renderBanner([annotation({ id: 'm1', placementStatus: PlacementStatus.Moved })]);
    expect(screen.queryByTestId('reanchor-banner')).not.toBeInTheDocument();
  });
});
