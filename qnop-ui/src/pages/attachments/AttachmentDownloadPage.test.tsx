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
import { MemoryRouter, Route, Routes } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { downloadAttachment } from '../../api/attachments';
import { ProtectedRoute } from '../../components/auth/ProtectedRoute';
import { useAuthStore } from '../../stores/authStore';
import { AttachmentDownloadPage } from './AttachmentDownloadPage';

vi.mock('../../api/attachments', () => ({ downloadAttachment: vi.fn() }));

const PATH = '/attachments/doc-1/att-1';

function renderPage() {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[PATH]}>
        <Routes>
          <Route path="/login" element={<div>Login form</div>} />
          <Route
            path="/attachments/:documentId/:attachmentId"
            element={
              <ProtectedRoute>
                <AttachmentDownloadPage />
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ isAuthenticated: true });
});

describe('AttachmentDownloadPage', () => {
  it('sends a signed-out visitor to the login form', () => {
    useAuthStore.setState({ isAuthenticated: false });

    renderPage();

    // The whole reason the report links here and not at the API: a browser
    // following a link out of a Word file carries no token, so the visitor has
    // to be able to sign in and come back.
    expect(screen.getByText('Login form')).toBeInTheDocument();
  });

  it('does not download until asked', () => {
    renderPage();

    // A page that fires a download on open leaves a blocked browser looking as
    // though nothing happened.
    expect(downloadAttachment).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Download/ })).toBeEnabled();
  });

  it('fetches the attachment and names what it saved', async () => {
    const user = userEvent.setup();
    vi.mocked(downloadAttachment).mockResolvedValue('notes.pdf');
    renderPage();

    await user.click(screen.getByRole('button', { name: /^Download$/ }));

    await waitFor(() => expect(downloadAttachment).toHaveBeenCalledWith('doc-1', 'att-1'));
    expect(await screen.findByText('notes.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Download again/ })).toBeInTheDocument();
  });

  it('explains a refusal without guessing which kind it was', async () => {
    const user = userEvent.setup();
    vi.mocked(downloadAttachment).mockRejectedValue(new Error('404'));
    renderPage();

    await user.click(screen.getByRole('button', { name: /^Download$/ }));

    // The API answers 404 for "gone" and "not yours" alike, so the page must not
    // pretend to know which — saying "no access" would leak that it exists.
    expect(await screen.findByText(/not available/i)).toBeInTheDocument();
    expect(screen.getByText(/deleted, or the review may not be shared/i)).toBeInTheDocument();
  });
});
