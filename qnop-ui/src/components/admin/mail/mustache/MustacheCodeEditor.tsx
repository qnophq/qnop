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

import { forwardRef, useImperativeHandle, useMemo, useRef } from 'react';
import Box from '@mui/material/Box';
import { useTheme } from '@mui/material/styles';
import { html } from '@codemirror/lang-html';
import { EditorView } from '@codemirror/view';
import CodeMirror, { type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { mustacheExtension } from './mustacheExtension';

/** Imperative handle the editor exposes so chip clicks can insert text at the caret. */
export interface MustacheEditorHandle {
  insertAtCursor: (text: string) => void;
  focus: () => void;
}

interface MustacheCodeEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** The template's closed placeholder set, for highlighting + completion. */
  placeholders: string[];
  language?: 'plain' | 'html';
  minHeight?: string;
  onFocus?: () => void;
  readOnly?: boolean;
}

/**
 * A CodeMirror editor with Mustache highlighting and `{{ … }}` completion (issue #144). HTML mode
 * adds the HTML language with tag closing. Exposes {@link MustacheEditorHandle} so the placeholder
 * chips insert `{{name}}` at the caret. Imported lazily by the edit page to keep CodeMirror out of
 * the main bundle.
 */
export const MustacheCodeEditor = forwardRef<MustacheEditorHandle, MustacheCodeEditorProps>(
  function MustacheCodeEditor(
    { value, onChange, placeholders, language = 'plain', minHeight = '220px', onFocus, readOnly },
    ref,
  ) {
    const cmRef = useRef<ReactCodeMirrorRef>(null);
    const theme = useTheme();

    useImperativeHandle(ref, () => ({
      insertAtCursor(text: string) {
        const view = cmRef.current?.view;
        if (!view) {
          return;
        }
        const { from, to } = view.state.selection.main;
        view.dispatch({
          changes: { from, to, insert: text },
          selection: { anchor: from + text.length },
        });
        view.focus();
      },
      focus() {
        cmRef.current?.view?.focus();
      },
    }));

    const extensions = useMemo(
      () => [
        EditorView.lineWrapping,
        mustacheExtension(placeholders, language),
        ...(language === 'html' ? [html({ autoCloseTags: true, matchClosingTags: true })] : []),
      ],
      [placeholders, language],
    );

    return (
      <Box
        sx={{
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1.5,
          overflow: 'hidden',
          transition: (t) => t.transitions.create(['border-color', 'box-shadow']),
          '&:focus-within': {
            borderColor: theme.qnop.brand.blue,
            boxShadow: theme.qnop.focusRing,
          },
          '& .cm-editor': { fontSize: 13.5 },
          '& .cm-content': { fontFamily: theme.typography.fontFamily },
          '& .cm-editor.cm-focused': { outline: 'none' },
        }}
      >
        <CodeMirror
          ref={cmRef}
          value={value}
          onChange={onChange}
          onFocus={onFocus}
          extensions={extensions}
          editable={!readOnly}
          theme={theme.qnop.mode}
          minHeight={minHeight}
          basicSetup={{
            lineNumbers: language === 'html',
            foldGutter: language === 'html',
            highlightActiveLine: false,
            highlightActiveLineGutter: false,
            autocompletion: true,
            bracketMatching: true,
            closeBrackets: language === 'html',
          }}
        />
      </Box>
    );
  },
);
