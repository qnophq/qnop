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

import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { buildTheme } from '../../theme/theme';
import { DeleteReviewDialog } from './DeleteReviewDialog';
import { documentsApi } from '../../api/config';

vi.mock('../../api/config', () => ({
  documentsApi: { deleteDocument: vi.fn() },
  axiosInstance: { get: vi.fn(), post: vi.fn() },
}));

const DOC_ID = '3f6f6f6f-0000-0000-0000-000000000001';
const TITLE = 'Vendor agreement 2026';

const onClose = vi.fn();
const onDeleted = vi.fn();

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

function renderDialog(open = true) {
  return render(
    <DeleteReviewDialog
      documentId={DOC_ID}
      title={TITLE}
      open={open}
      onClose={onClose}
      versionCount={3}
      annotationCount={12}
      onDeleted={onDeleted}
    />,
    { wrapper },
  );
}

const deleteButton = () => screen.getByRole('button', { name: /Delete permanently/ });

beforeEach(() => {
  vi.clearAllMocks();
});

describe('DeleteReviewDialog', () => {
  it('refuses to arm until the title is typed exactly', () => {
    renderDialog();

    // A confirm button alone would not survive an admin working down a list of
    // look-alike reviews; there is no undo behind this one (issue #421).
    expect(deleteButton()).toBeDisabled();

    fireEvent.change(screen.getByTestId('delete-confirm-input'), {
      target: { value: 'Vendor agreement' },
    });
    expect(deleteButton()).toBeDisabled();

    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: TITLE } });
    expect(deleteButton()).toBeEnabled();
  });

  it('says what the deletion takes, in numbers', () => {
    renderDialog();

    // "This cannot be undone" is a warning; "3 versions and 12 annotations" is
    // information the reader can weigh.
    expect(screen.getByText(/3 versions and 12 annotations/)).toBeInTheDocument();
    expect(screen.getByText(/cannot be undone/)).toBeInTheDocument();
  });

  it('names archiving as the reversible alternative', () => {
    renderDialog();

    // The owner's tool. Offering it here is what keeps deletion from being used
    // as a tidy-up for something that only needed hiding.
    expect(screen.getByText(/use Archive/)).toBeInTheDocument();
  });

  it('deletes once confirmed and reports back', async () => {
    vi.mocked(documentsApi.deleteDocument).mockResolvedValue({ data: undefined } as never);
    renderDialog();

    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: TITLE } });
    fireEvent.click(deleteButton());

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    expect(documentsApi.deleteDocument).toHaveBeenCalledWith({ documentId: DOC_ID });
  });

  it('keeps the dialog open when the server refuses', async () => {
    vi.mocked(documentsApi.deleteDocument).mockRejectedValue({
      isAxiosError: true,
      response: { status: 403, data: { code: 'FORBIDDEN', detail: 'only admins may delete' } },
    });
    renderDialog();

    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: TITLE } });
    fireEvent.click(deleteButton());

    // The shared helper deliberately does not surface raw server detail; what
    // matters here is that the dialog stays put with the typed confirmation
    // intact, so the reader can see what failed and retry.
    expect(await screen.findByText(/could not be deleted/)).toBeInTheDocument();
    expect(onDeleted).not.toHaveBeenCalled();
  });

  it('forgets what was typed when it is reopened', () => {
    const { rerender } = renderDialog();
    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: TITLE } });
    expect(deleteButton()).toBeEnabled();

    rerender(
      <DeleteReviewDialog
        documentId={DOC_ID}
        title={TITLE}
        open={false}
        onClose={onClose}
        onDeleted={onDeleted}
      />,
    );
    rerender(
      <DeleteReviewDialog
        documentId={DOC_ID}
        title={TITLE}
        open
        onClose={onClose}
        onDeleted={onDeleted}
      />,
    );

    // Otherwise an abandoned attempt leaves the next review one click from gone.
    expect(deleteButton()).toBeDisabled();
  });
});
