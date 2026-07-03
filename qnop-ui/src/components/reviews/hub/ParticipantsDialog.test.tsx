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
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ParticipantListResponse, PrincipalListResponse } from '../../../api/generated';
import { ParticipantKind } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { documentsApi, principalsApi } from '../../../api/config';
import { ParticipantsDialog } from './ParticipantsDialog';

vi.mock('../../../api/config', () => ({
  axiosInstance: { post: vi.fn(), get: vi.fn() },
  documentsApi: {
    listParticipants: vi.fn(),
    addParticipant: vi.fn(),
    removeParticipant: vi.fn(),
  },
  principalsApi: { searchPrincipals: vi.fn() },
  reviewWorkflowApi: {
    getDocumentWorkflow: vi.fn(),
    transitionDocumentWorkflow: vi.fn(),
  },
}));

const DOC_ID = '00000000-0000-0000-0000-0000000000d1';
const ME = '00000000-0000-0000-0000-0000000000aa';

const PARTICIPANTS: ParticipantListResponse = {
  participants: [
    { id: 'p1', kind: ParticipantKind.User, principalId: 'u-max', displayName: 'Max Member' },
    { id: 'p2', kind: ParticipantKind.Team, principalId: 't-alpha', displayName: 'Team Alpha' },
  ],
};

const PRINCIPALS: PrincipalListResponse = {
  principals: [
    { id: 'u-max', kind: ParticipantKind.User, displayName: 'Max Member' },
    { id: ME, kind: ParticipantKind.User, displayName: 'Me Myself' },
    { id: 'u-ava', kind: ParticipantKind.User, displayName: 'Avery Auditor' },
  ],
};

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <ThemeProvider theme={buildTheme('light')}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ThemeProvider>
  );
}

const notify = vi.fn();

function renderDialog(isOwner: boolean) {
  return render(
    <ParticipantsDialog
      documentId={DOC_ID}
      open
      onClose={vi.fn()}
      isOwner={isOwner}
      ownUserId={ME}
      notify={notify}
    />,
    { wrapper },
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(documentsApi.listParticipants).mockResolvedValue({
    data: PARTICIPANTS,
  } as Awaited<ReturnType<typeof documentsApi.listParticipants>>);
  vi.mocked(principalsApi.searchPrincipals).mockResolvedValue({
    data: PRINCIPALS,
  } as Awaited<ReturnType<typeof principalsApi.searchPrincipals>>);
  vi.mocked(documentsApi.addParticipant).mockResolvedValue({ data: { id: 'p3' } } as Awaited<
    ReturnType<typeof documentsApi.addParticipant>
  >);
  vi.mocked(documentsApi.removeParticipant).mockResolvedValue(
    undefined as unknown as Awaited<ReturnType<typeof documentsApi.removeParticipant>>,
  );
});

describe('ParticipantsDialog — read-only for participants', () => {
  it('lists reviewers without management controls', async () => {
    renderDialog(false);

    expect(await screen.findByText('Max Member')).toBeInTheDocument();
    expect(screen.getByText('Team Alpha')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Add people or teams…')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();
  });
});

describe('ParticipantsDialog — owner management', () => {
  it('offers only principals that are not the owner or already reviewing', async () => {
    renderDialog(true);

    expect(await screen.findByTestId('add-principal-u-ava')).toBeInTheDocument();
    expect(screen.queryByTestId('add-principal-u-max')).not.toBeInTheDocument();
    expect(screen.queryByTestId(`add-principal-${ME}`)).not.toBeInTheDocument();
  });

  it('adds a principal and notifies', async () => {
    renderDialog(true);

    fireEvent.click(await screen.findByTestId('add-principal-u-ava'));

    await waitFor(() =>
      expect(documentsApi.addParticipant).toHaveBeenCalledWith({
        documentId: DOC_ID,
        participantCreateRequest: { userId: 'u-ava' },
      }),
    );
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Avery Auditor added.'));
  });

  it('removes a participant and notifies', async () => {
    renderDialog(true);

    fireEvent.click(await screen.findByRole('button', { name: 'Remove Max Member' }));

    await waitFor(() =>
      expect(documentsApi.removeParticipant).toHaveBeenCalledWith({
        documentId: DOC_ID,
        participantId: 'p1',
      }),
    );
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Max Member removed.'));
  });

  it('maps a duplicate conflict (409) to friendly text', async () => {
    const conflict = new AxiosError('conflict');
    conflict.response = {
      status: 409,
      data: { code: 'DUPLICATE_PARTICIPANT', message: 'server prose' },
    } as AxiosResponse;
    vi.mocked(documentsApi.addParticipant).mockRejectedValue(conflict);
    renderDialog(true);

    fireEvent.click(await screen.findByTestId('add-principal-u-ava'));

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Already a reviewer on this document.', 'error'),
    );
  });
});
