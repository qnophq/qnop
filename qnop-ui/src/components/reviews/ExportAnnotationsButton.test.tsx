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
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { documentKeys } from '../../api/hooks/useDocuments';
import { buildTheme } from '../../theme/theme';
import { downloadAnnotationExport } from '../../api/annotationExport';
import { ExportAnnotationsButton } from './ExportAnnotationsButton';

vi.mock('../../api/annotationExport', () => ({
  downloadAnnotationExport: vi.fn(),
}));

const COUNTS = { all: 12, open: 5, resolved: 7 };

function renderButton(onError = vi.fn()) {
  // The button reads the document's title from the cache to prefill the
  // filename. Seeding the cache rather than stubbing the fetch mirrors reality:
  // both surfaces have already loaded this document by the time the button
  // renders.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(documentKeys.detail('doc-1'), { id: 'doc-1', title: 'Vendor Agreement' });
  render(
    <QueryClientProvider client={client}>
      <ThemeProvider theme={buildTheme('light')}>
        <ExportAnnotationsButton documentId="doc-1" version={3} counts={COUNTS} onError={onError} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return onError;
}

/** Opens the wizard and walks to the field step. */
async function openWizard(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Export' }));
  return screen.findByRole('dialog');
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  vi.mocked(downloadAnnotationExport).mockResolvedValue(undefined);
});

describe('ExportAnnotationsButton', () => {
  it('opens the wizard instead of downloading straight away', async () => {
    const user = userEvent.setup();
    renderButton();

    expect(downloadAnnotationExport).not.toHaveBeenCalled();
    await openWizard(user);

    expect(screen.getByRole('heading', { name: /What do you need/ })).toBeInTheDocument();
    expect(downloadAnnotationExport).not.toHaveBeenCalled();
  });

  it('exports everything with every column by default', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    const [, , options] = vi.mocked(downloadAnnotationExport).mock.calls[0];
    expect(options?.scope).toBe('all');
    // All eleven columns, because everything starts selected.
    expect(options?.fields).toHaveLength(11);
    expect(options?.comments).toBe(true);
  });

  it('can leave the comment threads out', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);
    await user.click(screen.getByRole('button', { name: /Next/ }));

    await user.click(screen.getByRole('switch', { name: /Comment threads/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.comments).toBe(false);
  });

  it('says on the summary line that the threads are coming along', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    // The threads land on a second sheet, which is invisible until the file is
    // open — so the wizard says so while there is still a choice to make.
    expect(screen.getByText(/with comment threads/)).toBeInTheDocument();
  });

  it('narrows the export to the chosen scope', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    await user.click(screen.getByRole('button', { name: /Open only/ }));
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.scope).toBe('open');
  });

  it('drops deselected columns but keeps the required one', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);
    await user.click(screen.getByRole('button', { name: /Next/ }));

    await user.click(screen.getByRole('button', { name: 'None' }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    // Clearing everything still leaves the task key: rows with nothing naming
    // them would be useless, so it cannot be switched off.
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.fields).toEqual(['taskKey']);
  });

  it('shows what the download will contain before it runs', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    // An export has no preview and no undo once it lands in the downloads folder.
    expect(screen.getByText(/12 annotations/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Resolved only/ }));
    expect(screen.getByText(/7 annotations/)).toBeInTheDocument();
  });

  it('remembers the configuration for the next export', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);
    await user.click(screen.getByRole('button', { name: /Open only/ }));
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));
    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());

    // Reopening comes back with the previous scope rather than the default.
    await user.click(screen.getByRole('button', { name: 'Export' }));
    expect(await screen.findByText(/5 annotations/)).toBeInTheDocument();
  });

  it('keeps the wizard open on failure so the selection is not lost', async () => {
    const user = userEvent.setup();
    vi.mocked(downloadAnnotationExport).mockRejectedValue(new Error('boom'));
    const onError = renderButton();
    await openWizard(user);
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(onError).toHaveBeenCalledWith(expect.stringMatching(/could not/i)));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('exports HTML like any other format', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    // It shipped in #637; nothing about it is conditional on the server the way
    // PDF's converter is.
    await user.click(screen.getByRole('button', { name: /^HTML/ }));
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.format).toBe('html');
  });

  it('exports the chosen format', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    await user.click(screen.getByRole('button', { name: /^Word/ }));
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.format).toBe('docx');
  });

  it('describes the fields in the words of the chosen format', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    // A spreadsheet has columns; a report does not, and calling them columns
    // there would describe a file the user is not about to get.
    expect(screen.getByText(/of 11 columns/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Word/ }));
    expect(screen.getByText(/of 11 details/)).toBeInTheDocument();
  });

  it('can leave the branding logo out', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    // The logo and the filename live on the first step: they describe the file
    // being produced, not what goes inside it.
    await user.click(screen.getByRole('switch', { name: /Branding logo/ }));
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.logo).toBe(false);
  });

  it('sends the chosen date format', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);
    await user.click(screen.getByRole('button', { name: /Next/ }));

    // Picked by its sample, because that is the part that disambiguates.
    await user.click(screen.getByRole('button', { name: /04\.03\.2026 14:30/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.dateFormat).toBe('european');
  });

  it('prefills the filename from the review title', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    const field = await screen.findByLabelText('File name');
    await waitFor(() => expect(field).toHaveValue('vendor-agreement-annotations'));
    // The extension follows the format and is shown beside the field, not typed
    // into it — scoped to the field, since the format cards name extensions too.
    const wrapper = field.closest('.MuiInputBase-root') as HTMLElement;
    expect(within(wrapper).getByText('.xlsx')).toBeInTheDocument();
  });

  it('sends a filename the user typed instead', async () => {
    const user = userEvent.setup();
    renderButton();
    await openWizard(user);

    const field = await screen.findByLabelText('File name');
    await user.clear(field);
    await user.type(field, 'Q3 findings');
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /^Export$/ }));

    await waitFor(() => expect(downloadAnnotationExport).toHaveBeenCalled());
    expect(vi.mocked(downloadAnnotationExport).mock.calls[0][2]?.fileName).toBe('Q3 findings');
  });
});
