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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { CopyTextButton } from './CopyTextButton';
import { copyToClipboard } from '../../utils/clipboard';

vi.mock('../../utils/clipboard', () => ({ copyToClipboard: vi.fn() }));

const mockedCopy = vi.mocked(copyToClipboard);

beforeEach(() => vi.clearAllMocks());

describe('CopyTextButton (issue #478)', () => {
  it('copies the payload, toasts success, and never toggles the host card', async () => {
    mockedCopy.mockResolvedValue(true);
    const notify = vi.fn();
    const cardClick = vi.fn();
    render(
      <div role="presentation" onClick={cardClick}>
        <CopyTextButton
          text="the liability clause"
          notify={notify}
          label="Copy quote"
          copiedMessage="Quote copied."
        />
      </div>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Copy quote' }));

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Quote copied.', 'success'));
    expect(mockedCopy).toHaveBeenCalledWith('the liability clause');
    expect(cardClick).not.toHaveBeenCalled();
  });

  it('degrades to an error toast when the clipboard is unavailable', async () => {
    mockedCopy.mockResolvedValue(false);
    const notify = vi.fn();
    render(<CopyTextButton text="x" notify={notify} label="Copy comment" />);

    fireEvent.click(screen.getByRole('button', { name: 'Copy comment' }));

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Could not copy to the clipboard.', 'error'),
    );
  });
});
