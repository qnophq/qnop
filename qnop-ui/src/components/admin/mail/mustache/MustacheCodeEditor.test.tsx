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

import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import { MustacheCodeEditor } from './MustacheCodeEditor';

/**
 * Mounts the real CodeMirror editor — the page tests mock it, which is how a second
 * @codemirror/state instance crashed the mail template editor unnoticed (issue #857): CodeMirror
 * then rejects its own extensions at EditorState.create.
 */
describe('MustacheCodeEditor', () => {
  it.each(['plain', 'html'] as const)('mounts with the %s extensions', (language) => {
    const { container } = renderWithProviders(
      <MustacheCodeEditor
        value="Hi {{recipientName}}, welcome to {{siteName}}."
        onChange={vi.fn()}
        placeholders={['recipientName', 'siteName']}
        language={language}
      />,
    );

    expect(container.querySelector('.cm-editor')).not.toBeNull();
    expect(container.querySelector('.cm-content')?.textContent).toContain('{{recipientName}}');
  });
});
