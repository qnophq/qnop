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
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { downloadAnnotationExport } from '../../api/annotationExport';
import { ExportAnnotationsButton } from './ExportAnnotationsButton';

vi.mock('../../api/annotationExport', () => ({
  downloadAnnotationExport: vi.fn(),
}));

function renderButton(onError = vi.fn()) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <ExportAnnotationsButton documentId="doc-1" version={3} onError={onError} />
    </ThemeProvider>,
  );
  return onError;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(downloadAnnotationExport).mockResolvedValue(undefined);
});

describe('ExportAnnotationsButton', () => {
  it('is a labelled menu trigger, not a direct download', async () => {
    const user = userEvent.setup();
    renderButton();

    const trigger = screen.getByRole('button', { name: 'Export' });
    // Icon-only, so the accessible name has to come from the label.
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(downloadAnnotationExport).not.toHaveBeenCalled();

    await user.click(trigger);
    expect(await screen.findByRole('menuitem', { name: /Export to Excel/ })).toBeInTheDocument();
  });

  it('downloads the chosen format for the current review and version', async () => {
    const user = userEvent.setup();
    renderButton();

    await user.click(screen.getByRole('button', { name: 'Export' }));
    await user.click(await screen.findByRole('menuitem', { name: /Export to Excel/ }));

    expect(downloadAnnotationExport).toHaveBeenCalledWith('doc-1', 3);
    // The menu closes on choice rather than lingering over the download.
    await waitFor(() => expect(screen.queryByRole('menuitem')).not.toBeInTheDocument());
  });

  it('hands a failure to the surface that owns the toast', async () => {
    const user = userEvent.setup();
    vi.mocked(downloadAnnotationExport).mockRejectedValue(new Error('boom'));
    const onError = renderButton();

    await user.click(screen.getByRole('button', { name: 'Export' }));
    await user.click(await screen.findByRole('menuitem', { name: /Export to Excel/ }));

    await waitFor(() => expect(onError).toHaveBeenCalledWith(expect.stringMatching(/could not/i)));
    // …and the trigger is usable again for another try.
    expect(screen.getByRole('button', { name: 'Export' })).toBeEnabled();
  });
});
