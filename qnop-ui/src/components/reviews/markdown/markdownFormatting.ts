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

/**
 * Selection-aware Markdown formatting for the composer toolbar (issue #445).
 * Pure text transforms — no DOM — so every action is unit-testable. The caller
 * owns the textarea; it feeds the current value + selection in and applies the
 * returned replacement span (which keeps the native undo stack intact when
 * spliced via `insertText`).
 */

export type MarkdownAction =
  | 'bold'
  | 'italic'
  | 'strikethrough'
  | 'code'
  | 'heading'
  | 'link'
  | 'image'
  | 'table'
  | 'bulletList'
  | 'orderedList'
  | 'quote'
  | 'codeBlock';

export interface TextSelection {
  value: string;
  start: number;
  end: number;
}

export interface FormattingResult {
  /** The full next value. */
  value: string;
  /** Where the selection should land afterwards. */
  selectionStart: number;
  selectionEnd: number;
  /** The minimal replaced span in the ORIGINAL value plus its replacement. */
  spanStart: number;
  spanEnd: number;
  replacement: string;
}

const URL_PATTERN = /^https?:\/\/\S+$/;

function result(
  value: string,
  spanStart: number,
  spanEnd: number,
  replacement: string,
  selectionStart: number,
  selectionEnd: number,
): FormattingResult {
  return {
    value: value.slice(0, spanStart) + replacement + value.slice(spanEnd),
    selectionStart,
    selectionEnd,
    spanStart,
    spanEnd,
    replacement,
  };
}

/** Wraps the selection in `marker`; unwraps when it is already wrapped (toggle). */
function wrapInline(selection: TextSelection, marker: string): FormattingResult {
  const { value, start, end } = selection;
  const selected = value.slice(start, end);
  const m = marker.length;

  // The markers travel inside the selection: `**bold**` selected as a whole.
  if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
    const inner = selected.slice(m, selected.length - m);
    return result(value, start, end, inner, start, start + inner.length);
  }

  // The markers sit directly around the selection: `**|bold|**`.
  if (
    start >= m &&
    value.slice(start - m, start) === marker &&
    value.slice(end, end + m) === marker
  ) {
    return result(value, start - m, end + m, selected, start - m, start - m + selected.length);
  }

  return result(
    value,
    start,
    end,
    marker + selected + marker,
    start + m,
    start + m + selected.length,
  );
}

/**
 * Builds a `[text](url)` link or `![alt](url)` image. A selected URL becomes
 * the target (the text/alt placeholder stays selected); any other selection
 * becomes the label (the `url` placeholder stays selected) — the next
 * keystroke fills the blank.
 */
function makeLinkLike(
  selection: TextSelection,
  prefix: '' | '!',
  textPlaceholder: string,
): FormattingResult {
  const { value, start, end } = selection;
  const selected = value.slice(start, end);
  if (!selected || URL_PATTERN.test(selected)) {
    const replacement = `${prefix}[${textPlaceholder}](${selected || 'url'})`;
    const textStart = start + prefix.length + 1;
    return result(value, start, end, replacement, textStart, textStart + textPlaceholder.length);
  }
  const replacement = `${prefix}[${selected}](url)`;
  const urlStart = start + prefix.length + selected.length + 3;
  return result(value, start, end, replacement, urlStart, urlStart + 3);
}

/**
 * The 3-column table skeleton, appended below the current line's content so an
 * inline caret never tears a sentence apart; the first header cell stays
 * selected for immediate typing.
 */
const TABLE_TEMPLATE = [
  '| Column 1 | Column 2 | Column 3 |',
  '| -------- | -------- | -------- |',
  '|          |          |          |',
].join('\n');

function insertTable(selection: TextSelection): FormattingResult {
  const { value } = selection;
  const { lineStart, lineEnd } = lineSpan(value, selection.start, selection.end);
  const block = value.slice(lineStart, lineEnd);
  const prefix = block.trim().length > 0 ? `${block}\n\n` : block;
  const cellStart = lineStart + prefix.length + 2;
  return result(
    value,
    lineStart,
    lineEnd,
    prefix + TABLE_TEMPLATE,
    cellStart,
    cellStart + 'Column 1'.length,
  );
}

/** Expands the selection to whole lines. */
function lineSpan(value: string, start: number, end: number) {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1;
  const nextBreak = value.indexOf('\n', end);
  return { lineStart, lineEnd: nextBreak === -1 ? value.length : nextBreak };
}

/** Prefixes every selected line (bullet / number / quote); strips when all carry it (toggle). */
function prefixLines(
  selection: TextSelection,
  prefixFor: (index: number) => string,
  pattern: RegExp,
): FormattingResult {
  const { value } = selection;
  const { lineStart, lineEnd } = lineSpan(value, selection.start, selection.end);
  const lines = value.slice(lineStart, lineEnd).split('\n');
  const allPrefixed = lines.every((line) => pattern.test(line));
  const replacement = lines
    .map((line, index) => (allPrefixed ? line.replace(pattern, '') : prefixFor(index) + line))
    .join('\n');
  return result(value, lineStart, lineEnd, replacement, lineStart, lineStart + replacement.length);
}

/** Wraps the selected lines in ``` fences; removes them when already fenced (toggle). */
function fenceCodeBlock(selection: TextSelection): FormattingResult {
  const { value } = selection;
  const { lineStart, lineEnd } = lineSpan(value, selection.start, selection.end);
  const lines = value.slice(lineStart, lineEnd).split('\n');
  if (lines.length >= 2 && lines[0] === '```' && lines[lines.length - 1] === '```') {
    const inner = lines.slice(1, -1).join('\n');
    return result(value, lineStart, lineEnd, inner, lineStart, lineStart + inner.length);
  }
  const replacement = '```\n' + value.slice(lineStart, lineEnd) + '\n```';
  return result(value, lineStart, lineEnd, replacement, lineStart, lineStart + replacement.length);
}

export function applyMarkdownAction(
  selection: TextSelection,
  action: MarkdownAction,
): FormattingResult {
  switch (action) {
    case 'bold':
      return wrapInline(selection, '**');
    case 'italic':
      return wrapInline(selection, '_');
    case 'strikethrough':
      return wrapInline(selection, '~~');
    case 'code':
      return wrapInline(selection, '`');
    case 'heading':
      // Comments cap headings small (Markdown.tsx), so one level is enough;
      // the strip pattern removes a heading of ANY level (toggle off an H2).
      return prefixLines(selection, () => '### ', /^#{1,6} /);
    case 'link':
      return makeLinkLike(selection, '', 'text');
    case 'image':
      return makeLinkLike(selection, '!', 'alt');
    case 'table':
      return insertTable(selection);
    case 'bulletList':
      return prefixLines(selection, () => '- ', /^- /);
    case 'orderedList':
      return prefixLines(selection, (index) => `${index + 1}. `, /^\d+\. /);
    case 'quote':
      return prefixLines(selection, () => '> ', /^> /);
    case 'codeBlock':
      return fenceCodeBlock(selection);
  }
}
