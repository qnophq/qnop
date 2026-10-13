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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { principalsApi } from '../../api/config';
import { applyLocale } from '../../i18n';
import { useAuthStore } from '../../stores/authStore';
import { buildTheme } from '../../theme/theme';
import { NewReviewPage } from './NewReviewPage';

vi.mock('../../api/config', () => ({
  axiosInstance: { post: vi.fn(), get: vi.fn() },
  documentsApi: { addParticipant: vi.fn() },
  principalsApi: { searchPrincipals: vi.fn() },
  reviewWorkflowApi: { transitionDocumentWorkflow: vi.fn() },
}));
vi.mock('../../api/hooks/useReviews', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/hooks/useReviews')>()),
  useReviewCapacity: vi.fn(() => ({ limit: 0, used: 0, full: false, isLoading: false })),
}));
vi.mock('../../api/hooks/useConfig', () => ({
  useConfig: () => ({ data: { upload: { maxDocumentSizeMb: 50 } } }),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reviews/new']}>
          <Routes>
            <Route path="/reviews/new" element={<NewReviewPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

function pick(file: File) {
  fireEvent.change(screen.getByTestId('wizard-dropzone-input'), { target: { files: [file] } });
}

/** The new-review wizard in German (issue #836): steps, checklist and validation messages. */
describe('new-review wizard in German', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useAuthStore.setState({ userId: 'me', isAuthenticated: true });
    vi.mocked(principalsApi.searchPrincipals).mockResolvedValue({
      data: { principals: [] },
    } as unknown as Awaited<ReturnType<typeof principalsApi.searchPrincipals>>);
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the step strip, the dropzone and the launch checklist in German', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Neues Review' })).toBeInTheDocument();
    expect(screen.getByText('Schritt 1 von 3')).toBeInTheDocument();
    expect(screen.getByText('Zusammenfassung & Start')).toBeInTheDocument();
    expect(screen.getByText('Leg dein Dokument hier ab oder wähle eine Datei')).toBeInTheDocument();
    expect(screen.getByText('PDF · max. 50 MB')).toBeInTheDocument();
    expect(screen.getByText('Startbereitschaft')).toBeInTheDocument();
    expect(screen.getByText('startet sofort')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Weiter/ })).toBeDisabled();
  });

  it('rejects an unsupported file with a German message', () => {
    renderPage();

    pick(new File(['hi'], 'notes.odt', { type: 'application/vnd.oasis.opendocument.text' }));

    expect(screen.getByText('Unterstützt werden nur Dokumente im Format PDF.')).toBeInTheDocument();
  });

  it('validates the slug in German when leaving step 1', () => {
    renderPage();
    pick(new File(['%PDF-1.4'], 'Vertrag.pdf', { type: 'application/pdf' }));

    expect(screen.getByDisplayValue('Vertrag')).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('wizard-slug-input'), { target: { value: 'ab' } });
    fireEvent.click(screen.getByRole('button', { name: /Weiter/ }));

    expect(screen.getByText('Das Kürzel muss 3–64 Zeichen lang sein.')).toBeInTheDocument();
    expect(screen.getByText('Titel gesetzt')).toBeInTheDocument();
  });
});
