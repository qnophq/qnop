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
import { buildTheme } from '../../../theme/theme';
import { acceptedUploads } from '../wizard/wizardModel';
import { NewVersionDialog } from './NewVersionDialog';
import { axiosInstance } from '../../../api/config';

vi.mock('../../../api/config', () => ({
  axiosInstance: { post: vi.fn() },
  documentsApi: {},
}));

const DOC_ID = '3f6f6f6f-0000-0000-0000-000000000001';

const notify = vi.fn();
const onClose = vi.fn();
const onUploaded = vi.fn();

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
    <NewVersionDialog
      documentId={DOC_ID}
      open={open}
      onClose={onClose}
      maxSizeMb={50}
      accepted={acceptedUploads(['PDF', 'DOCX'])}
      notify={notify}
      onUploaded={onUploaded}
    />,
    { wrapper },
  );
}

const pdf = () => new File(['%PDF-1.4'], 'contract-v2.pdf', { type: 'application/pdf' });

beforeEach(() => {
  vi.clearAllMocks();
});

describe('NewVersionDialog', () => {
  it('says what a new version does to the annotations already on the review', () => {
    renderDialog();

    // The old button said nothing about this at all, and re-anchoring (ADR-0009)
    // is the part a reader would want to know before replacing the document.
    expect(screen.getByText(/re-anchored to the new document/)).toBeInTheDocument();
  });

  it('accepts a dropped file, which the old file-chooser button could not', () => {
    renderDialog();

    fireEvent.drop(screen.getByTestId('new-version-dropzone'), {
      dataTransfer: { files: [pdf()] },
    });

    expect(screen.getByText('contract-v2.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Upload version/ })).toBeEnabled();
  });

  it('offers Word only where the server takes it', () => {
    renderDialog();

    // Same rule as the wizard (issue #343): the dropzone must not invite an upload
    // the server would refuse with 415.
    expect(screen.getByTestId('new-version-dropzone-input')).toHaveAttribute(
      'accept',
      expect.stringContaining('.docx'),
    );
    expect(screen.getByText(/PDF or Word/)).toBeInTheDocument();
  });

  it('keeps the dialog and the chosen file when the upload fails', async () => {
    vi.mocked(axiosInstance.post).mockRejectedValue({
      isAxiosError: true,
      response: { status: 500, data: {} },
    });
    renderDialog();

    fireEvent.change(screen.getByTestId('new-version-dropzone-input'), {
      target: { files: [pdf()] },
    });
    fireEvent.click(screen.getByRole('button', { name: /Upload version/ }));

    // Closing on failure would throw away the file and make the reader find it
    // again, so the error is shown where the retry is.
    expect(await screen.findByText(/could not be uploaded/)).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByText('contract-v2.pdf')).toBeInTheDocument();
  });

  it('reports progress while the bytes travel', async () => {
    // The hook reports upload progress and the old button discarded it; a 50 MB
    // document over a slow link looked identical to a hang.
    let reportProgress: ((event: { loaded: number; total: number }) => void) | undefined;
    vi.mocked(axiosInstance.post).mockImplementation((_url, _body, config) => {
      reportProgress = (config as { onUploadProgress?: typeof reportProgress })?.onUploadProgress;
      return new Promise(() => {}); // never settles: the upload stays in flight
    });
    renderDialog();

    fireEvent.change(screen.getByTestId('new-version-dropzone-input'), {
      target: { files: [pdf()] },
    });
    fireEvent.click(screen.getByRole('button', { name: /Upload version/ }));

    await waitFor(() => expect(reportProgress).toBeDefined());
    reportProgress?.({ loaded: 40, total: 100 });

    expect(await screen.findByText(/Uploading… 40%/)).toBeInTheDocument();
    // Nothing is dismissible mid-flight — closing would leave a request with
    // nowhere to report back to.
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  });

  it('starts clean when reopened after a cancelled attempt', () => {
    const { rerender } = renderDialog();

    fireEvent.change(screen.getByTestId('new-version-dropzone-input'), {
      target: { files: [pdf()] },
    });
    expect(screen.getByText('contract-v2.pdf')).toBeInTheDocument();

    rerender(
      <NewVersionDialog
        documentId={DOC_ID}
        open={false}
        onClose={onClose}
        maxSizeMb={50}
        accepted={acceptedUploads(['PDF'])}
        notify={notify}
        onUploaded={onUploaded}
      />,
    );
    rerender(
      <NewVersionDialog
        documentId={DOC_ID}
        open={true}
        onClose={onClose}
        maxSizeMb={50}
        accepted={acceptedUploads(['PDF'])}
        notify={notify}
        onUploaded={onUploaded}
      />,
    );

    // A file left over from an abandoned attempt would be uploaded by the next
    // person who opens this and presses the button without looking.
    expect(screen.queryByText('contract-v2.pdf')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Upload version/ })).toBeDisabled();
  });
});
