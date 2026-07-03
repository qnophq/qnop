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
import type { DiffChange } from '../../../api/generated';
import { DiffChangeType } from '../../../api/generated';
import {
  changePageNumber,
  diffStats,
  excerpt,
  paintsOnSide,
  surfaceChangeBoxes,
  wordCount,
} from './diffModel';

const box = (x: number) => ({ x, y: 0.1, width: 0.2, height: 0.02 });

const change = (
  type: DiffChangeType,
  fromSurfaces: number[],
  toSurfaces: number[],
  overrides: Partial<DiffChange> = {},
): DiffChange => ({
  type,
  fromText: fromSurfaces.length > 0 ? 'old words here' : '',
  toText: toSurfaces.length > 0 ? 'new words' : '',
  fromLocations: fromSurfaces.map((surfaceIndex, i) => ({ surfaceIndex, box: box(i * 0.1) })),
  toLocations: toSurfaces.map((surfaceIndex, i) => ({ surfaceIndex, box: box(i * 0.1) })),
  ...overrides,
});

describe('paintsOnSide', () => {
  it('routes deletions to the baseline, insertions to the newer version, changes to both', () => {
    const deleted = change(DiffChangeType.Deleted, [0], []);
    const inserted = change(DiffChangeType.Inserted, [], [0]);
    const changed = change(DiffChangeType.Changed, [0], [0]);

    expect(paintsOnSide(deleted, 'from')).toBe(true);
    expect(paintsOnSide(deleted, 'to')).toBe(false);
    expect(paintsOnSide(inserted, 'from')).toBe(false);
    expect(paintsOnSide(inserted, 'to')).toBe(true);
    expect(paintsOnSide(changed, 'from')).toBe(true);
    expect(paintsOnSide(changed, 'to')).toBe(true);
  });
});

describe('surfaceChangeBoxes', () => {
  it('groups only the matching surface boxes and keeps the change index', () => {
    const changes = [
      change(DiffChangeType.Deleted, [0, 1], []),
      change(DiffChangeType.Inserted, [], [1]),
      change(DiffChangeType.Changed, [1], [1]),
    ];

    const fromPage1 = surfaceChangeBoxes(changes, 'from', 1);

    expect(fromPage1.map((entry) => entry.changeIndex)).toEqual([0, 2]);
    expect(fromPage1[0].boxes).toHaveLength(1);
    // The inserted change has no baseline geometry: absent from the from pane.
    expect(surfaceChangeBoxes(changes, 'from', 1).some((e) => e.changeIndex === 1)).toBe(false);
    expect(surfaceChangeBoxes(changes, 'to', 1).map((e) => e.changeIndex)).toEqual([1, 2]);
  });
});

describe('changePageNumber', () => {
  it('prefers the newer version location and falls back to the baseline', () => {
    expect(changePageNumber(change(DiffChangeType.Changed, [3], [2]))).toBe(3);
    expect(changePageNumber(change(DiffChangeType.Deleted, [4], []))).toBe(5);
    expect(changePageNumber(change(DiffChangeType.Inserted, [], []))).toBeNull();
  });
});

describe('diffStats', () => {
  it('counts words per direction and collects touched pages ascending', () => {
    const stats = diffStats([
      change(DiffChangeType.Deleted, [2], []), // -3 words
      change(DiffChangeType.Inserted, [], [0]), // +2 words
      change(DiffChangeType.Changed, [1], [1]), // -3 / +2 words
    ]);

    expect(stats.addedWords).toBe(4);
    expect(stats.removedWords).toBe(6);
    expect(stats.pages).toEqual([1, 2, 3]);
  });

  it('is empty for an empty diff', () => {
    expect(diffStats([])).toEqual({ addedWords: 0, removedWords: 0, pages: [] });
  });
});

describe('wordCount', () => {
  it('splits on any whitespace and ignores blank text', () => {
    expect(wordCount('  one\n two\tthree ')).toBe(3);
    expect(wordCount('   ')).toBe(0);
    expect(wordCount('')).toBe(0);
  });
});

describe('excerpt', () => {
  it('collapses whitespace and cuts on a word boundary with an ellipsis', () => {
    expect(excerpt('a  b\nc')).toBe('a b c');
    const long = `${'word '.repeat(40)}tail`;
    const cut = excerpt(long, 60);
    expect(cut.length).toBeLessThanOrEqual(61);
    expect(cut.endsWith('…')).toBe(true);
    expect(cut).not.toContain('  ');
  });
});
