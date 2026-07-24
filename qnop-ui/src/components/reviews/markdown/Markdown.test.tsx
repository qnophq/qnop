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
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { axiosInstance } from '../../../api/config';
import { Markdown } from './Markdown';

vi.mock('../../../api/config', () => ({
  axiosInstance: { get: vi.fn().mockResolvedValue({ data: new Blob(['png']) }) },
}));

// jsdom has no object URLs — the attachment loader needs both ends stubbed.
URL.createObjectURL = vi.fn(() => 'blob:attachment');
URL.revokeObjectURL = vi.fn();

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

  it('renders a resolved @mention as an in-app profile link, not an external anchor (#462)', () => {
    const id = '018f5a3e-0000-7000-8000-000000000001';
    const { getByTestId } = render(
      <MemoryRouter>
        <ThemeProvider theme={buildTheme('light')}>
          <Markdown>{`hey [@Alice](mention:${id}) look`}</Markdown>
        </ThemeProvider>
      </MemoryRouter>,
    );
    const mention = getByTestId('mention-link');
    expect(mention).toHaveTextContent('@Alice');
    // Links into the app to the profile — the mention: scheme never reaches the DOM as an href.
    expect(mention).toHaveAttribute('href', `/users/${id}`);
  });

  it('renders a fenced code block', () => {
    const { container } = renderMd('```js\nconst x = 1;\n```');
    expect(container.querySelector('pre code')).toHaveTextContent('const x = 1;');
  });

  it('syntax-highlights a language-tagged fence (issue #445 follow-up)', () => {
    const { container } = renderMd('```js\nconst x = 1; // answer\n```');
    expect(container.querySelector('pre code .hljs-keyword')).toHaveTextContent('const');
    expect(container.querySelector('pre code .hljs-comment')).toHaveTextContent('// answer');
  });

  it('leaves an untagged fence unhighlighted (no auto-detection, as on GitHub)', () => {
    const { container } = renderMd('```\nconst x = 1;\n```');
    expect(container.querySelector('pre code .hljs-keyword')).toBeNull();
  });

  it('renders a single newline as a hard line break (issue #445)', () => {
    // Comments are chat-like prose: Enter must produce a visible break, as in
    // Slack/GitHub comments — CommonMark alone would collapse it into a space.
    const { container } = renderMd('line one\nline two');
    expect(container.querySelectorAll('br')).toHaveLength(1);
    expect(container.querySelectorAll('p')).toHaveLength(1);
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

  it('loads app attachment sources with the bearer and shows a blob URL (issue #446)', async () => {
    const { container } = renderMd('![shot](/api/v1/documents/d1/attachments/a1)');

    await waitFor(() =>
      expect(container.querySelector('img')).toHaveAttribute('src', 'blob:attachment'),
    );
    expect(vi.mocked(axiosInstance.get)).toHaveBeenCalledWith('/documents/d1/attachments/a1', {
      responseType: 'blob',
    });
  });

  it('renders an app attachment link as a downloading file chip (issue #446)', () => {
    const { container, getByTestId } = renderMd(
      '[report.pdf](/api/v1/documents/d1/attachments/a2)',
    );

    const chip = getByTestId('attachment-link');
    expect(chip).toHaveTextContent('report.pdf');
    // No new-tab navigation — the chip downloads through the bearer fetch.
    expect(chip).not.toHaveAttribute('target');
    // External links keep the plain new-tab anchor.
    expect(container.querySelector('a[target="_blank"]')).toBeNull();
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
