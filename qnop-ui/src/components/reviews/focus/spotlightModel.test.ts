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

import { describe, expect, it } from 'vitest';
import type { AnnotationView, RenderedSurface } from '../../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../../api/generated';
import { placedInOrder, spotlightForAnchor, walkPosition } from './spotlightModel';

const annotation = (id: string, surfaceIndex: number | null, y = 0.2): AnnotationView => ({
  id,
  documentId: 'd1',
  authorId: 'u1',
  status: AnnotationStatus.Open,
  placementStatus: surfaceIndex === null ? PlacementStatus.Orphaned : PlacementStatus.Placed,
  anchor:
    surfaceIndex === null
      ? undefined
      : { region: { surfaceIndex, box: { x: 0.1, y, width: 0.3, height: 0.02 } } },
  commentCount: 1,
  reactions: [],
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-01T10:00:00Z',
});

const SURFACES: RenderedSurface[] = [{ index: 0, width: 600, height: 800, textSpans: [] }];

describe('spotlightForAnchor', () => {
  it('hugs the painted geometry exactly — no margin exposing undimmed page', () => {
    const spotlight = spotlightForAnchor(
      { region: { surfaceIndex: 0, box: { x: 0.1, y: 0.2, width: 0.3, height: 0.02 } } },
      SURFACES,
    );

    expect(spotlight.surfaceIndex).toBe(0);
    expect(spotlight.box.x).toBeCloseTo(0.1, 10);
    expect(spotlight.box.y).toBeCloseTo(0.2, 10);
    expect(spotlight.box.width).toBeCloseTo(0.3, 10);
    expect(spotlight.box.height).toBeCloseTo(0.02, 10);
  });

  it('clamps at the page edges', () => {
    const spotlight = spotlightForAnchor(
      { region: { surfaceIndex: 0, box: { x: -0.05, y: 0, width: 1.2, height: 0.05 } } },
      SURFACES,
    );

    expect(spotlight.box.x).toBe(0);
    expect(spotlight.box.x + spotlight.box.width).toBeLessThanOrEqual(1);
  });
});

describe('placedInOrder / walkPosition', () => {
  const list = [
    annotation('c', 1, 0.1),
    annotation('a', 0, 0.2),
    annotation('orphan', null),
    annotation('b', 0, 0.6),
  ];

  it('orders placed annotations by document position, skipping unplaced ones', () => {
    expect(placedInOrder(list).map((a) => a.id)).toEqual(['a', 'b', 'c']);
  });

  it('reports the walk position with its neighbours', () => {
    expect(walkPosition(list, 'b')).toEqual({ index: 1, count: 3, prevId: 'a', nextId: 'c' });
    expect(walkPosition(list, 'a')?.prevId).toBeNull();
    expect(walkPosition(list, 'c')?.nextId).toBeNull();
  });

  it('returns null for annotations without a placement', () => {
    expect(walkPosition(list, 'orphan')).toBeNull();
  });
});
