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

import {
  autocompletion,
  type CompletionContext,
  type CompletionResult,
  type CompletionSource,
} from '@codemirror/autocomplete';
import { htmlCompletionSource } from '@codemirror/lang-html';
import type { Extension } from '@codemirror/state';
import {
  Decoration,
  type DecorationSet,
  EditorView,
  MatchDecorator,
  ViewPlugin,
  type ViewUpdate,
} from '@codemirror/view';

const knownMark = Decoration.mark({ class: 'cm-mustache-known' });
const unknownMark = Decoration.mark({ class: 'cm-mustache-unknown' });

/** A view plugin that tints known placeholders and flags unknown ones, kept in sync on edits. */
function highlightPlugin(placeholders: Set<string>) {
  const matcher = new MatchDecorator({
    // A fresh regex per plugin: MatchDecorator mutates lastIndex, so instances must not share one.
    regexp: /\{\{\{?\s*([a-zA-Z_]\w*)\s*\}?\}\}/g,
    decoration: (match) => (placeholders.has(match[1]) ? knownMark : unknownMark),
  });
  return ViewPlugin.fromClass(
    class {
      decorations: DecorationSet;
      constructor(view: EditorView) {
        this.decorations = matcher.createDeco(view);
      }
      update(update: ViewUpdate) {
        this.decorations = matcher.updateDeco(update, this.decorations);
      }
    },
    { decorations: (plugin) => plugin.decorations },
  );
}

/** Completes the closed placeholder set when the caret sits inside an open `{{ … }}`. */
function placeholderCompletions(placeholders: string[]) {
  return (context: CompletionContext): CompletionResult | null => {
    const before = context.matchBefore(/\{\{\{?\s*\w*/);
    if (!before) {
      return null;
    }
    const typed = before.text.replace(/^\{\{\{?\s*/, '');
    return {
      from: context.pos - typed.length,
      options: placeholders.map((name) => ({ label: name, type: 'variable' })),
      validFor: /^\w*$/,
    };
  };
}

/** Brand-aligned colours: known = action blue, unknown = amber with a wavy underline. */
const mustacheTheme = EditorView.baseTheme({
  '.cm-mustache-known': { color: '#0B6FBC', fontWeight: '600' },
  '.cm-mustache-unknown': { color: '#8A5E00', textDecoration: 'underline wavy #F5B83D' },
});

/**
 * CodeMirror extension that highlights Mustache placeholders (issue #144): known names from the
 * template's closed set are tinted, unknown ones are flagged, and typing inside `{{ … }}` offers
 * the known names as completions. In HTML mode the language's tag/attribute completions are kept
 * alongside the placeholder source (the placeholder source returns null outside `{{ … }}`).
 */
export function mustacheExtension(
  placeholders: string[],
  language: 'plain' | 'html' = 'plain',
): Extension {
  const sources: CompletionSource[] = [placeholderCompletions(placeholders)];
  if (language === 'html') {
    sources.push(htmlCompletionSource);
  }
  return [
    highlightPlugin(new Set(placeholders)),
    mustacheTheme,
    autocompletion({ override: sources }),
  ];
}
