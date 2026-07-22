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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { DismissControl } from './DismissControl';

function renderControl(onDismiss = vi.fn(), disabled = false) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <DismissControl disabled={disabled} onDismiss={onDismiss} />
    </ThemeProvider>,
  );
  return onDismiss;
}

describe('DismissControl', () => {
  it('starts collapsed as a quiet text button and unfolds on demand', () => {
    renderControl();

    expect(screen.getByRole('button', { name: /dismiss/i })).toBeInTheDocument();
    expect(screen.queryByLabelText('Dismissal justification (required)')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(screen.getByLabelText('Dismissal justification (required)')).toBeInTheDocument();
    expect(screen.getByText(/the author may reopen/i)).toBeInTheDocument();
  });

  it('refuses to submit without a justification', () => {
    const onDismiss = renderControl();
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));

    const submit = screen.getByRole('button', { name: 'Dismiss' });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Dismissal justification (required)'), {
      target: { value: '   ' },
    });
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeDisabled();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('submits the trimmed justification', () => {
    const onDismiss = renderControl();
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));

    fireEvent.change(screen.getByLabelText('Dismissal justification (required)'), {
      target: { value: '  author left the project  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    expect(onDismiss).toHaveBeenCalledWith('author left the project');
  });

  it('collapses again on Cancel', () => {
    renderControl();
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByLabelText('Dismissal justification (required)')).toBeNull();
  });
});
