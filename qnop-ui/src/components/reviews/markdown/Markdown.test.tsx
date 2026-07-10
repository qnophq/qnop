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
import { render } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { Markdown } from './Markdown';

function renderMd(md: string) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <Markdown>{md}</Markdown>
    </ThemeProvider>,
  );
}

describe('Markdown render (#427)', () => {
  it('renders GFM formatting as the expected elements', () => {
    const { container } = renderMd(
      '**bold** _italic_ ~~struck~~ `code`\n\n- a\n- b\n\n> quoted\n\n| h |\n| - |\n| c |',
    );
    expect(container.querySelector('strong')).toHaveTextContent('bold');
    expect(container.querySelector('em')).toHaveTextContent('italic');
    expect(container.querySelector('del')).toHaveTextContent('struck');
    expect(container.querySelector('code')).toHaveTextContent('code');
    expect(container.querySelectorAll('li')).toHaveLength(2);
    expect(container.querySelector('blockquote')).toHaveTextContent('quoted');
    expect(container.querySelector('table')).toBeInTheDocument();
  });

  it('renders a fenced code block', () => {
    const { container } = renderMd('```js\nconst x = 1;\n```');
    expect(container.querySelector('pre code')).toHaveTextContent('const x = 1;');
  });

  it('renders links as safe new-tab anchors', () => {
    const { container } = renderMd('[the clause](https://example.com/x)');
    const a = container.querySelector('a');
    expect(a).toHaveAttribute('href', 'https://example.com/x');
    expect(a).toHaveAttribute('target', '_blank');
    expect(a).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('renders images with no-referrer + lazy loading', () => {
    const { container } = renderMd('![a diagram](https://example.com/d.png)');
    const img = container.querySelector('img');
    expect(img).toHaveAttribute('src', 'https://example.com/d.png');
    expect(img).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(img).toHaveAttribute('loading', 'lazy');
  });
});

describe('Markdown sanitisation — no XSS (#427)', () => {
  it('never renders a <script> from raw HTML in the body', () => {
    const { container } = renderMd('Hello <script>alert(1)</script> world');
    expect(container.querySelector('script')).toBeNull();
  });

  it('drops event-handler attributes / does not execute raw <img onerror>', () => {
    const { container } = renderMd('<img src="x" onerror="alert(1)">');
    // Raw HTML is not parsed into elements, so no executing image appears.
    expect(container.querySelector('img[onerror]')).toBeNull();
  });

  it('strips a javascript: link scheme', () => {
    const { container } = renderMd('[click me](javascript:alert(1))');
    const href = container.querySelector('a')?.getAttribute('href') ?? '';
    expect(href.toLowerCase()).not.toContain('javascript:');
  });

  it('strips a data: link scheme', () => {
    const { container } = renderMd('[x](data:text/html,<script>alert(1)</script>)');
    const href = container.querySelector('a')?.getAttribute('href') ?? '';
    expect(href.toLowerCase().startsWith('data:')).toBe(false);
    expect(container.querySelector('script')).toBeNull();
  });
});
