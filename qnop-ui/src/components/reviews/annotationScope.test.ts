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
import type { AnnotationView } from '../../api/generated';
import { PlacementStatus } from '../../api/generated';
import { isDocumentScoped } from './annotationScope';

const REGION_ANCHOR = { region: { surfaceIndex: 0, box: { x: 0, y: 0, width: 0.1, height: 0.1 } } };

describe('isDocumentScoped (#395)', () => {
  it('is true when the annotation carries no anchor', () => {
    expect(isDocumentScoped({ anchor: undefined })).toBe(true);
  });

  it('is false for a located annotation', () => {
    expect(isDocumentScoped({ anchor: REGION_ANCHOR } as AnnotationView)).toBe(false);
  });

  it('is false for an orphaned annotation — it keeps its anchor', () => {
    // The key invariant: orphaned retains its anchor (placement status ORPHANED), so it never
    // looks document-scoped.
    expect(
      isDocumentScoped({
        anchor: REGION_ANCHOR,
        placementStatus: PlacementStatus.Orphaned,
      } as AnnotationView),
    ).toBe(false);
  });
});
