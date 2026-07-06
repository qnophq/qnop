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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { CopyLinkButton } from './CopyLinkButton';

const URL = 'https://qnop.example/reviews/doc-1?annotation=a1';
const originalClipboard = navigator.clipboard;

function renderButton(notify = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <CopyLinkButton url={URL} notify={notify} />
    </ThemeProvider>,
  );
  return notify;
}

afterEach(() => {
  Object.assign(navigator, { clipboard: originalClipboard });
});

describe('CopyLinkButton', () => {
  it('writes the url to the clipboard and confirms with a success toast', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const notify = renderButton();

    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(URL));
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Link copied.', 'success'));
  });

  it('degrades to an error toast when the clipboard is unavailable', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('insecure context'));
    Object.assign(navigator, { clipboard: { writeText } });
    const notify = renderButton();

    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }));

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Could not copy the link.', 'error'));
  });
});

describe('CopyLinkButton — event isolation', () => {
  it('does not bubble the click to an enclosing handler (would toggle the card)', () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const onParentClick = vi.fn();
    // Models the clickable annotation card the affordance sits inside; a role
    // button (not a native one) avoids nesting two real buttons in the test.
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <div
          role="button"
          tabIndex={0}
          aria-label="card"
          onClick={onParentClick}
          onKeyDown={() => {}}
        >
          <CopyLinkButton url={URL} notify={vi.fn()} />
        </div>
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }));

    expect(onParentClick).not.toHaveBeenCalled();
  });
});
