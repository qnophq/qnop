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

import type { DiffChange, DiffLocation, NormalizedBox } from '../../../api/generated';
import { DiffChangeType } from '../../../api/generated';

/**
 * Pure model helpers for the version-diff view (ADR-0034, issue #252). All
 * geometry comes from the server's located changes — the client only groups
 * and counts; it never derives positions from its own rendering (ADR-0032).
 */

/** Which pane a box list belongs to: `from` = baseline, `to` = newer version. */
export type DiffSide = 'from' | 'to';

/**
 * Card/highlight presentation of a change type: the label text and the badge
 * tone (mapping onto `theme.qnop.badge` and the MUI palette). Follows the
 * design prototype's diff colour language — insertions green, deletions red,
 * in-place changes amber.
 */
export const CHANGE_KIND: Record<
  DiffChangeType,
  { label: string; tone: 'green' | 'red' | 'amber' }
> = {
  [DiffChangeType.Inserted]: { label: 'Added', tone: 'green' },
  [DiffChangeType.Deleted]: { label: 'Deleted', tone: 'red' },
  [DiffChangeType.Changed]: { label: 'Changed', tone: 'amber' },
};

/** The locations of a change on one side. */
export function sideLocations(change: DiffChange, side: DiffSide): DiffLocation[] {
  return side === 'from' ? change.fromLocations : change.toLocations;
}

/**
 * Whether a change paints on the given pane: deletions exist only in the
 * baseline, insertions only in the newer version, in-place changes in both.
 */
export function paintsOnSide(change: DiffChange, side: DiffSide): boolean {
  if (change.type === DiffChangeType.Changed) return true;
  return side === 'from'
    ? change.type === DiffChangeType.Deleted
    : change.type === DiffChangeType.Inserted;
}

/** One change's boxes on one surface of one pane, keyed by its list index. */
export interface SurfaceChangeBoxes {
  /** The change's index in the response list — the card↔highlight link key. */
  changeIndex: number;
  type: DiffChangeType;
  boxes: NormalizedBox[];
}

/** The changes that paint on `surfaceIndex` of the given pane, with their boxes. */
export function surfaceChangeBoxes(
  changes: DiffChange[],
  side: DiffSide,
  surfaceIndex: number,
): SurfaceChangeBoxes[] {
  const result: SurfaceChangeBoxes[] = [];
  changes.forEach((change, changeIndex) => {
    if (!paintsOnSide(change, side)) return;
    const boxes = sideLocations(change, side)
      .filter((location) => location.surfaceIndex === surfaceIndex)
      .map((location) => location.box);
    if (boxes.length > 0) result.push({ changeIndex, type: change.type, boxes });
  });
  return result;
}

/**
 * The 1-based page a change lives on — the newer version's first location,
 * falling back to the baseline's (a DELETED change has no `to` geometry).
 * Null when the change carries no geometry at all (no text layer).
 */
export function changePageNumber(change: DiffChange): number | null {
  const location = change.toLocations[0] ?? change.fromLocations[0];
  return location ? location.surfaceIndex + 1 : null;
}

/**
 * A stable identity for a change within its (immutable) diff — a React list key
 * that survives re-renders without leaning on the array index. Each change is a
 * distinct contiguous region, so its type plus its anchor location's surface and
 * box is unique; a change with no geometry falls back to its text.
 */
export function changeKey(change: DiffChange): string {
  const location = change.toLocations[0] ?? change.fromLocations[0];
  const where = location
    ? `${location.surfaceIndex}:${location.box.x},${location.box.y},${location.box.width},${location.box.height}`
    : `t:${change.fromText}>${change.toText}`;
  return `${change.type}#${where}`;
}

/** Words in a text — the unit of the diff statistics. */
export function wordCount(text: string): number {
  const trimmed = text.trim();
  return trimmed.length === 0 ? 0 : trimmed.split(/\s+/).length;
}

export interface DiffStats {
  addedWords: number;
  removedWords: number;
  /** 1-based page numbers touched by any change, ascending. */
  pages: number[];
}

/** Aggregate statistics over all changes (the sidebar's stats block). */
export function diffStats(changes: DiffChange[]): DiffStats {
  let addedWords = 0;
  let removedWords = 0;
  const pages = new Set<number>();
  for (const change of changes) {
    addedWords += wordCount(change.toText);
    removedWords += wordCount(change.fromText);
    for (const location of [...change.fromLocations, ...change.toLocations]) {
      pages.add(location.surfaceIndex + 1);
    }
  }
  return { addedWords, removedWords, pages: [...pages].sort((a, b) => a - b) };
}

/** Shortens a card excerpt to a readable length, on a word boundary. */
export function excerpt(text: string, maxChars = 140): string {
  const clean = text.replace(/\s+/g, ' ').trim();
  if (clean.length <= maxChars) return clean;
  const cut = clean.slice(0, maxChars);
  const lastSpace = cut.lastIndexOf(' ');
  return `${cut.slice(0, lastSpace > maxChars / 2 ? lastSpace : maxChars)}…`;
}
