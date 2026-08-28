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
import type { RenderedTextSpan } from '../../../api/generated';
import { keyboardCaretMove, spanAtOffset } from './keyboardSelection';

const SPANS: RenderedTextSpan[] = [
  { text: 'Hello', startOffset: 0, endOffset: 5, box: { x: 0, y: 0, width: 0.5, height: 0.02 } },
  { text: 'Hi', startOffset: 6, endOffset: 8, box: { x: 0, y: 0.05, width: 0.2, height: 0.02 } },
];
const LENGTH = 8;

describe('keyboardCaretMove (issue #460)', () => {
  it('steps one character and clamps at the text ends', () => {
    expect(keyboardCaretMove(SPANS, 0, 'ArrowRight', LENGTH)).toBe(1);
    expect(keyboardCaretMove(SPANS, 0, 'ArrowLeft', LENGTH)).toBe(0);
    expect(keyboardCaretMove(SPANS, 8, 'ArrowRight', LENGTH)).toBe(8);
  });

  it('keeps the column across lines, clamped to the shorter line', () => {
    expect(keyboardCaretMove(SPANS, 4, 'ArrowDown', LENGTH)).toBe(8);
    expect(keyboardCaretMove(SPANS, 7, 'ArrowUp', LENGTH)).toBe(1);
    expect(keyboardCaretMove(SPANS, 7, 'ArrowDown', LENGTH)).toBeNull();
  });

  it('jumps to the line ends with Home and End', () => {
    expect(keyboardCaretMove(SPANS, 7, 'Home', LENGTH)).toBe(6);
    expect(keyboardCaretMove(SPANS, 1, 'End', LENGTH)).toBe(5);
  });

  it('leaves unrelated keys alone', () => {
    expect(keyboardCaretMove(SPANS, 1, 'a', LENGTH)).toBeNull();
    expect(keyboardCaretMove(SPANS, 1, 'Tab', LENGTH)).toBeNull();
  });

  it('resolves the span at an offset, including the trailing boundary', () => {
    expect(spanAtOffset(SPANS, 5)?.text).toBe('Hello');
    expect(spanAtOffset(SPANS, 6)?.text).toBe('Hi');
    expect(spanAtOffset([], 0)).toBeNull();
  });
});
