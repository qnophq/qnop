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

import type {
  Anchor,
  AnnotationView,
  NormalizedBox,
  RenderedSurface,
} from '../../../api/generated';
import {
  clampBox,
  compareAnnotationsByPosition,
  highlightBoxesForAnchor,
  unionBoxes,
} from '../viewer/anchoring';

/**
 * Pure geometry/ordering helpers for the focus view (issue #291). The
 * spotlight never derives from the client's own rendering — it grows from the
 * same stored anchor boxes the highlights paint with (ADR-0032).
 */

/** The sharp region of the scrim: one page and the box on it. */
export interface Spotlight {
  surfaceIndex: number;
  box: NormalizedBox;
}

/**
 * The clamped spotlight rect of an anchor on its surface — the union of
 * exactly the boxes the highlight layer paints (the pitch-grown marker bands,
 * or the region box). No extra padding: any margin would expose undimmed page
 * pixels around the mark, reading as a white frame against the veil.
 */
export function spotlightForAnchor(
  anchor: Anchor,
  surfaces: RenderedSurface[] | undefined,
): Spotlight {
  const surfaceIndex = anchor.region.surfaceIndex;
  const spans = surfaces?.find((surface) => surface.index === surfaceIndex)?.textSpans ?? [];
  const { boxes } = highlightBoxesForAnchor(anchor, spans);
  const union = unionBoxes(boxes) ?? anchor.region.box;
  return { surfaceIndex, box: clampBox(union) };
}

/** The spotlight of an annotation, or null when it has no placement here. */
export function spotlightForAnnotation(
  annotation: AnnotationView | undefined,
  surfaces: RenderedSurface[] | undefined,
): Spotlight | null {
  if (!annotation?.anchor) return null;
  return spotlightForAnchor(annotation.anchor, surfaces);
}

/** Placed annotations in document order — the prev/next walking order. */
export function placedInOrder(annotations: AnnotationView[]): AnnotationView[] {
  return annotations.filter((annotation) => annotation.anchor).sort(compareAnnotationsByPosition);
}

/** Where the active annotation sits in the walking order, with its neighbours. */
export interface WalkPosition {
  /** Zero-based position among the placed annotations. */
  index: number;
  count: number;
  prevId: string | null;
  nextId: string | null;
}

/** The walk position of `activeId`, or null when it is not placed here. */
export function walkPosition(annotations: AnnotationView[], activeId: string): WalkPosition | null {
  const ordered = placedInOrder(annotations);
  const index = ordered.findIndex((annotation) => annotation.id === activeId);
  if (index < 0) return null;
  return {
    index,
    count: ordered.length,
    prevId: index > 0 ? ordered[index - 1].id : null,
    nextId: index < ordered.length - 1 ? ordered[index + 1].id : null,
  };
}
