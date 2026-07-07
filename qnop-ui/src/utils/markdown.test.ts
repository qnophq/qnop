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
import { stripMarkdown } from './markdown';

describe('stripMarkdown (#427)', () => {
  it('drops emphasis, keeping the words', () => {
    expect(stripMarkdown('**bold** and _italic_ and `code`')).toBe('bold and italic and code');
  });

  it('keeps link text, drops the url syntax', () => {
    expect(stripMarkdown('see [the clause](https://example.com/x)')).toBe('see the clause');
  });

  it('flattens a multi-line list to a single readable line', () => {
    const out = stripMarkdown('- one\n- two\n- three');
    expect(out).toContain('one');
    expect(out).toContain('two');
    expect(out).not.toContain('- ');
    expect(out.split('\n')).toHaveLength(1);
  });

  it('strips headings and blockquotes to prose, and drops fenced code entirely', () => {
    expect(stripMarkdown('# Title')).toBe('Title');
    expect(stripMarkdown('> quoted')).toBe('quoted');
    // A code block is not title prose — strip-markdown removes it, so a
    // code-only opener leaves an empty title (taskTitle then falls back).
    expect(stripMarkdown('```js\nconst x = 1;\n```')).toBe('');
  });

  it('returns empty for empty/nullish input', () => {
    expect(stripMarkdown('')).toBe('');
    expect(stripMarkdown(null)).toBe('');
    expect(stripMarkdown(undefined)).toBe('');
  });

  it('is stable across repeated calls (cache hit returns the same value)', () => {
    const md = '**terminology** across the _contract_';
    expect(stripMarkdown(md)).toBe(stripMarkdown(md));
    expect(stripMarkdown(md)).toBe('terminology across the contract');
  });
});
