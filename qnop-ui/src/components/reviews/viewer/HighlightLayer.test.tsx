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

import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { HighlightLayer } from './HighlightLayer';

function wrap(children: ReactNode) {
  return <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>;
}

const annotation = (
  id: string,
  surfaceIndex: number,
  placementStatus: PlacementStatus = PlacementStatus.Placed,
  status: AnnotationStatus = AnnotationStatus.Open,
): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'u1',
  status,
  placementStatus,
  anchor: {
    region: { surfaceIndex, box: { x: 0.25, y: 0.5, width: 0.4, height: 0.05 } },
    textQuote: { quote: 'the quoted passage' },
  },
  commentCount: 1,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
});

describe('HighlightLayer', () => {
  it('draws only the annotations placed on its surface, at the stored box', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[annotation('on-surface', 0), annotation('other-surface', 1)]}
          surfaceIndex={0}
          activeAnnotationId={null}
          onSelect={() => {}}
        />,
      ),
    );

    const highlight = screen.getByTestId('highlight-on-surface');
    expect(highlight).toHaveStyle({ left: '25%', top: '50%', width: '40%', height: '5%' });
    expect(screen.queryByTestId('highlight-other-surface')).not.toBeInTheDocument();
  });

  it('selects the annotation on click and on keyboard activation', () => {
    const onSelect = vi.fn();
    render(
      wrap(
        <HighlightLayer
          annotations={[annotation('a1', 0)]}
          surfaceIndex={0}
          activeAnnotationId={null}
          onSelect={onSelect}
        />,
      ),
    );

    fireEvent.click(screen.getByTestId('highlight-a1'));
    fireEvent.keyDown(screen.getByTestId('highlight-a1'), { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenCalledWith('a1');
  });

  it('exposes the quote in the accessible name', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[annotation('a1', 0)]}
          surfaceIndex={0}
          activeAnnotationId={null}
          onSelect={() => {}}
        />,
      ),
    );

    expect(
      screen.getByRole('button', { name: /Annotation: the quoted passage/ }),
    ).toBeInTheDocument();
  });

  it('renders the pending preview box without pointer interaction', () => {
    render(
      wrap(
        <HighlightLayer
          annotations={[]}
          surfaceIndex={0}
          activeAnnotationId={null}
          onSelect={() => {}}
          pendingBox={{ x: 0.1, y: 0.2, width: 0.3, height: 0.1 }}
        />,
      ),
    );

    const pending = screen.getByTestId('pending-highlight');
    expect(pending).toHaveStyle({ left: '10%', top: '20%' });
    expect(pending).toHaveStyle({ 'pointer-events': 'none' });
  });
});
