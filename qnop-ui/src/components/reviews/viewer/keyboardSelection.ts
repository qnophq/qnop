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

import type { RenderedTextSpan } from '../../../api/generated';

/** The span whose offsets contain `offset` (a span's trailing boundary counts as its own). */
export function spanAtOffset(spans: RenderedTextSpan[], offset: number): RenderedTextSpan | null {
  return (
    spans.find((span) => offset >= span.startOffset && offset <= span.endOffset) ??
    (spans.length > 0 && offset >= spans[spans.length - 1].endOffset
      ? spans[spans.length - 1]
      : null)
  );
}

function clampOffset(offset: number, textLength: number): number {
  return Math.min(Math.max(offset, 0), textLength);
}

/**
 * Where the keyboard caret goes for a navigation key, in canonical-text
 * offsets (issue #460). Left/Right step one character; Up/Down keep the
 * column while moving one span (line) — clamped to the shorter line; Home/End
 * jump to the current line's ends. Null for any other key, so the caller lets
 * it bubble.
 */
export function keyboardCaretMove(
  spans: RenderedTextSpan[],
  focus: number,
  key: string,
  textLength: number,
): number | null {
  switch (key) {
    case 'ArrowRight':
      return clampOffset(focus + 1, textLength);
    case 'ArrowLeft':
      return clampOffset(focus - 1, textLength);
    case 'ArrowDown':
    case 'ArrowUp': {
      const index = spans.findIndex((span) => focus >= span.startOffset && focus <= span.endOffset);
      const target = spans[index + (key === 'ArrowDown' ? 1 : -1)];
      if (index < 0 || !target) return null;
      const column = focus - spans[index].startOffset;
      return clampOffset(target.startOffset + Math.min(column, target.text.length), textLength);
    }
    case 'Home':
    case 'End': {
      const span = spanAtOffset(spans, focus);
      if (!span) return null;
      return clampOffset(
        key === 'Home' ? span.startOffset : span.startOffset + span.text.length,
        textLength,
      );
    }
    default:
      return null;
  }
}
