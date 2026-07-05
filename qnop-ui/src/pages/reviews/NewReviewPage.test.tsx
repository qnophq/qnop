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
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import type { PrincipalListResponse, WorkflowStatus } from '../../api/generated';
import { ParticipantKind } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { axiosInstance, documentsApi, principalsApi, reviewWorkflowApi } from '../../api/config';
import { NewReviewPage } from './NewReviewPage';

vi.mock('../../api/config', () => ({
  axiosInstance: { post: vi.fn(), get: vi.fn() },
  documentsApi: { addParticipant: vi.fn() },
  principalsApi: { searchPrincipals: vi.fn() },
  reviewWorkflowApi: { transitionDocumentWorkflow: vi.fn() },
}));
vi.mock('../../api/hooks/useConfig', () => ({
  useConfig: () => ({ data: { upload: { maxDocumentSizeMb: 50 } } }),
}));

const ME = '00000000-0000-0000-0000-0000000000aa';
const DOC_ID = '00000000-0000-0000-0000-0000000000d1';

const PRINCIPALS: PrincipalListResponse = {
  principals: [
    { id: 'u-max', kind: ParticipantKind.User, displayName: 'Max Member' },
    { id: ME, kind: ParticipantKind.User, displayName: 'Me Myself' },
    { id: 't-alpha', kind: ParticipantKind.Team, displayName: 'Team Alpha' },
  ],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reviews/new']}>
          <Routes>
            <Route path="/reviews" element={<div data-testid="list-probe" />} />
            <Route path="/reviews/new" element={<NewReviewPage />} />
            <Route path="/reviews/:documentId" element={<div data-testid="detail-probe" />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

function pickPdf(name = 'NDA Acme Corp.pdf') {
  const file = new File(['%PDF-1.4'], name, { type: 'application/pdf' });
  fireEvent.change(screen.getByTestId('wizard-file-input'), { target: { files: [file] } });
  return file;
}

function goToStep2() {
  pickPdf();
  fireEvent.click(screen.getByRole('button', { name: /Next/ }));
}

async function goToStep3WithMax() {
  goToStep2();
  fireEvent.click(await screen.findByTestId('principal-u-max'));
  fireEvent.click(screen.getByRole('button', { name: /Next/ }));
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: ME, isAuthenticated: true });
  vi.mocked(principalsApi.searchPrincipals).mockResolvedValue({ data: PRINCIPALS } as Awaited<
    ReturnType<typeof principalsApi.searchPrincipals>
  >);
  vi.mocked(axiosInstance.post).mockResolvedValue({
    data: { documentId: DOC_ID, versionNumber: 1, extractionStatus: 'PENDING' },
  });
  vi.mocked(documentsApi.addParticipant).mockResolvedValue({ data: { id: 'p1' } } as Awaited<
    ReturnType<typeof documentsApi.addParticipant>
  >);
  const started: WorkflowStatus = { state: 'IN_REVIEW', allowedTransitions: [] };
  vi.mocked(reviewWorkflowApi.transitionDocumentWorkflow).mockResolvedValue({
    data: started,
  } as Awaited<ReturnType<typeof reviewWorkflowApi.transitionDocumentWorkflow>>);
});

describe('NewReviewPage — step 1', () => {
  it('blocks Next until a file is picked and prefills the title', () => {
    renderPage();

    expect(screen.getByRole('button', { name: /Next/ })).toBeDisabled();

    pickPdf();

    expect(screen.getByDisplayValue('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled();
  });

  it('rejects a non-PDF file with an error and keeps Next disabled', () => {
    renderPage();

    const file = new File(['hi'], 'notes.docx', { type: 'application/msword' });
    fireEvent.change(screen.getByTestId('wizard-file-input'), { target: { files: [file] } });

    expect(screen.getByText('Only PDF documents are supported.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Next/ })).toBeDisabled();
  });

  it('keeps a manually entered title when picking the file', () => {
    renderPage();

    fireEvent.change(screen.getByLabelText(/Review title/), { target: { value: 'My title' } });
    pickPdf();

    expect(screen.getByDisplayValue('My title')).toBeInTheDocument();
  });

  it('cancel returns to the overview', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /Cancel/ }));

    expect(screen.getByTestId('list-probe')).toBeInTheDocument();
  });
});

describe('NewReviewPage — step 2', () => {
  it('lists principals excluding myself and already selected ones', async () => {
    renderPage();
    goToStep2();

    expect(await screen.findByTestId('principal-u-max')).toBeInTheDocument();
    expect(screen.getByTestId('principal-t-alpha')).toBeInTheDocument();
    expect(screen.queryByTestId(`principal-${ME}`)).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('principal-u-max'));

    expect(screen.queryByTestId('principal-u-max')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove Max Member' })).toBeInTheDocument();
  });

  it('removes a selected reviewer again', async () => {
    renderPage();
    goToStep2();

    fireEvent.click(await screen.findByTestId('principal-u-max'));
    fireEvent.click(screen.getByRole('button', { name: 'Remove Max Member' }));

    expect(screen.getByText('No reviewers yet — you can also add them later.')).toBeInTheDocument();
    expect(await screen.findByTestId('principal-u-max')).toBeInTheDocument();
  });
});

describe('NewReviewPage — step 3 & submit', () => {
  it('creates the review, adds reviewers, starts it and navigates to it', async () => {
    renderPage();
    await goToStep3WithMax();

    expect(screen.getByText('NDA Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('Max Member')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    await waitFor(() => expect(screen.getByTestId('detail-probe')).toBeInTheDocument());
    const [url, form] = vi.mocked(axiosInstance.post).mock.calls[0];
    expect(url).toBe('/documents');
    expect((form as FormData).get('title')).toBe('NDA Acme Corp');
    expect(documentsApi.addParticipant).toHaveBeenCalledWith({
      documentId: DOC_ID,
      participantCreateRequest: { userId: 'u-max' },
    });
    expect(reviewWorkflowApi.transitionDocumentWorkflow).toHaveBeenCalledWith({
      documentId: DOC_ID,
      workflowTransitionRequest: { targetState: 'IN_REVIEW' },
    });
  });

  it('sends the anonymous flag when the toggle is on (issue #413)', async () => {
    renderPage();
    await goToStep3WithMax();

    // Default is off — nothing sent.
    expect(screen.getByRole('switch', { name: 'Anonymous review' })).not.toBeChecked();
    fireEvent.click(screen.getByRole('switch', { name: 'Anonymous review' }));
    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    await waitFor(() => expect(screen.getByTestId('detail-probe')).toBeInTheDocument());
    const [, form] = vi.mocked(axiosInstance.post).mock.calls[0];
    expect((form as FormData).get('anonymous')).toBe('true');
  });

  it('omits the anonymous flag by default', async () => {
    renderPage();
    await goToStep3WithMax();
    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    await waitFor(() => expect(screen.getByTestId('detail-probe')).toBeInTheDocument());
    const [, form] = vi.mocked(axiosInstance.post).mock.calls[0];
    expect((form as FormData).get('anonymous')).toBeNull();
  });

  it('sends a non-default thread participation policy, omitting OPEN (issue #413)', async () => {
    renderPage();
    await goToStep3WithMax();

    fireEvent.mouseDown(screen.getByLabelText('Thread participation'));
    fireEvent.click(screen.getByRole('option', { name: 'Private threads' }));
    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    await waitFor(() => expect(screen.getByTestId('detail-probe')).toBeInTheDocument());
    const [, form] = vi.mocked(axiosInstance.post).mock.calls[0];
    expect((form as FormData).get('threadParticipation')).toBe('PRIVATE');
  });

  it('omits threadParticipation for the default open policy', async () => {
    renderPage();
    await goToStep3WithMax();
    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    await waitFor(() => expect(screen.getByTestId('detail-probe')).toBeInTheDocument());
    const [, form] = vi.mocked(axiosInstance.post).mock.calls[0];
    expect((form as FormData).get('threadParticipation')).toBeNull();
  });

  it('skips the workflow transition when start-immediately is off', async () => {
    renderPage();
    await goToStep3WithMax();

    fireEvent.click(screen.getByRole('switch', { name: 'Start review immediately' }));
    fireEvent.click(screen.getByRole('button', { name: /Create review/ }));

    await waitFor(() => expect(screen.getByTestId('detail-probe')).toBeInTheDocument());
    expect(reviewWorkflowApi.transitionDocumentWorkflow).not.toHaveBeenCalled();
  });

  it('shows an error and stays on the wizard when the upload fails', async () => {
    vi.mocked(axiosInstance.post).mockRejectedValue(new Error('network down'));
    renderPage();
    await goToStep3WithMax();

    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    expect(await screen.findByText('The upload failed. Please try again.')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-probe')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Create & start review/ })).toBeEnabled();
  });

  it('reports a partial result when a follow-up step fails after the upload', async () => {
    vi.mocked(documentsApi.addParticipant).mockRejectedValue(new Error('conflict'));
    renderPage();
    await goToStep3WithMax();

    fireEvent.click(screen.getByRole('button', { name: /Create & start review/ }));

    expect(await screen.findByText(/some steps could not be completed/)).toBeInTheDocument();
    expect(screen.getByText(/add reviewer “Max Member”/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Open review/ }));
    expect(screen.getByTestId('detail-probe')).toBeInTheDocument();
  });
});
