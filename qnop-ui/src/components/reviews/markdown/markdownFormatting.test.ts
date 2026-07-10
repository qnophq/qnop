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
import { applyMarkdownAction } from './markdownFormatting';

/** Builds a selection from a template using `[` and `]` as the selection markers. */
function sel(template: string) {
  const start = template.indexOf('[');
  const end = template.indexOf(']') - 1;
  return { value: template.replace('[', '').replace(']', ''), start, end };
}

/** Renders a result back into the marker template for readable assertions. */
function mark(result: { value: string; selectionStart: number; selectionEnd: number }) {
  const { value, selectionStart: s, selectionEnd: e } = result;
  return `${value.slice(0, s)}[${value.slice(s, e)}]${value.slice(e)}`;
}

describe('applyMarkdownAction — inline wraps', () => {
  it('wraps the selection in bold markers and keeps the text selected', () => {
    expect(mark(applyMarkdownAction(sel('fix the [heading] first'), 'bold'))).toBe(
      'fix the **[heading]** first',
    );
  });

  it('unwraps a selection already wrapped in bold markers', () => {
    expect(mark(applyMarkdownAction(sel('fix the **[heading]** first'), 'bold'))).toBe(
      'fix the [heading] first',
    );
  });

  it('unwraps when the markers themselves are inside the selection', () => {
    expect(mark(applyMarkdownAction(sel('fix the [**heading**] first'), 'bold'))).toBe(
      'fix the [heading] first',
    );
  });

  it('inserts empty bold markers at the caret and parks the caret between them', () => {
    expect(mark(applyMarkdownAction(sel('note: []'), 'bold'))).toBe('note: **[]**');
  });

  it('wraps italic with underscores', () => {
    expect(mark(applyMarkdownAction(sel('[word]'), 'italic'))).toBe('_[word]_');
  });

  it('does not confuse the bold markers with an italic unwrap', () => {
    expect(mark(applyMarkdownAction(sel('**[word]**'), 'italic'))).toBe('**_[word]_**');
  });

  it('wraps strikethrough with double tildes', () => {
    expect(mark(applyMarkdownAction(sel('[gone]'), 'strikethrough'))).toBe('~~[gone]~~');
  });

  it('wraps inline code with backticks', () => {
    expect(mark(applyMarkdownAction(sel('run [pnpm dev] now'), 'code'))).toBe(
      'run `[pnpm dev]` now',
    );
  });
});

describe('applyMarkdownAction — link', () => {
  it('turns selected text into a link and selects the url placeholder', () => {
    expect(mark(applyMarkdownAction(sel('see [the docs] here'), 'link'))).toBe(
      'see [the docs]([url]) here',
    );
  });

  it('turns a selected url into a link and selects the text placeholder', () => {
    expect(mark(applyMarkdownAction(sel('[https://qnop.io/docs]'), 'link'))).toBe(
      '[[text]](https://qnop.io/docs)',
    );
  });

  it('inserts a full placeholder link at the caret and selects the text part', () => {
    expect(mark(applyMarkdownAction(sel('[]'), 'link'))).toBe('[[text]](url)');
  });
});

describe('applyMarkdownAction — line prefixes', () => {
  it('prefixes every selected line with a bullet and selects the block', () => {
    expect(mark(applyMarkdownAction(sel('[one\ntwo]'), 'bulletList'))).toBe('[- one\n- two]');
  });

  it('expands a mid-line selection to whole lines before prefixing', () => {
    expect(mark(applyMarkdownAction(sel('alpha be[t]a'), 'bulletList'))).toBe('[- alpha beta]');
  });

  it('removes the bullets when every selected line already carries one', () => {
    expect(mark(applyMarkdownAction(sel('[- one\n- two]'), 'bulletList'))).toBe('[one\ntwo]');
  });

  it('numbers the lines of an ordered list sequentially', () => {
    expect(mark(applyMarkdownAction(sel('[one\ntwo\nthree]'), 'orderedList'))).toBe(
      '[1. one\n2. two\n3. three]',
    );
  });

  it('strips the numbering when every selected line is already numbered', () => {
    expect(mark(applyMarkdownAction(sel('[1. one\n2. two]'), 'orderedList'))).toBe('[one\ntwo]');
  });

  it('prefixes a quote marker per line', () => {
    expect(mark(applyMarkdownAction(sel('[said\nthat]'), 'quote'))).toBe('[> said\n> that]');
  });

  it('starts a bullet on an empty caret line', () => {
    expect(mark(applyMarkdownAction(sel('[]'), 'bulletList'))).toBe('[- ]');
  });
});

describe('applyMarkdownAction — heading', () => {
  it('prefixes the selected line with a level-3 heading', () => {
    expect(mark(applyMarkdownAction(sel('[Summary]'), 'heading'))).toBe('[### Summary]');
  });

  it('removes the heading marker when the line already carries one', () => {
    expect(mark(applyMarkdownAction(sel('[### Summary]'), 'heading'))).toBe('[Summary]');
  });

  it('removes a heading marker of any level (toggle off an H2)', () => {
    expect(mark(applyMarkdownAction(sel('[## Summary]'), 'heading'))).toBe('[Summary]');
  });
});

describe('applyMarkdownAction — image', () => {
  it('turns selected text into an image alt and selects the url placeholder', () => {
    expect(mark(applyMarkdownAction(sel('see [the diagram] here'), 'image'))).toBe(
      'see ![the diagram]([url]) here',
    );
  });

  it('turns a selected url into an image target and selects the alt placeholder', () => {
    expect(mark(applyMarkdownAction(sel('[https://qnop.io/d.png]'), 'image'))).toBe(
      '![[alt]](https://qnop.io/d.png)',
    );
  });

  it('inserts a full placeholder image at the caret and selects the alt part', () => {
    expect(mark(applyMarkdownAction(sel('[]'), 'image'))).toBe('![[alt]](url)');
  });
});

describe('applyMarkdownAction — table', () => {
  it('inserts a table skeleton on an empty line and selects the first header cell', () => {
    const out = mark(applyMarkdownAction(sel('[]'), 'table'));
    expect(out).toBe(
      '| [Column 1] | Column 2 | Column 3 |\n| -------- | -------- | -------- |\n|          |          |          |',
    );
  });

  it('keeps the current line and appends the table below it', () => {
    const out = mark(applyMarkdownAction(sel('some notes[]'), 'table'));
    expect(out.startsWith('some notes\n\n| [Column 1] |')).toBe(true);
  });
});

describe('applyMarkdownAction — code block', () => {
  it('fences the selected lines and selects the fenced block', () => {
    expect(mark(applyMarkdownAction(sel('[const a = 1;]'), 'codeBlock'))).toBe(
      '[```\nconst a = 1;\n```]',
    );
  });

  it('keeps surrounding lines intact when fencing a middle line', () => {
    expect(mark(applyMarkdownAction(sel('before\n[code]\nafter'), 'codeBlock'))).toBe(
      'before\n[```\ncode\n```]\nafter',
    );
  });

  it('removes the fences when the selection is already fenced', () => {
    expect(mark(applyMarkdownAction(sel('[```\ncode\n```]'), 'codeBlock'))).toBe('[code]');
  });
});

describe('applyMarkdownAction — replacement span', () => {
  it('reports the minimal replaced span so callers can splice the text', () => {
    const result = applyMarkdownAction(sel('fix the [heading] first'), 'bold');
    expect(result.spanStart).toBe(8);
    expect(result.spanEnd).toBe(15);
    expect(result.replacement).toBe('**heading**');
    const { value, spanStart, spanEnd, replacement } = result;
    const original = 'fix the heading first';
    expect(original.slice(0, spanStart) + replacement + original.slice(spanEnd)).toBe(value);
  });
});
