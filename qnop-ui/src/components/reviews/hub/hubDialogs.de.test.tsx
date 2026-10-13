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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { AxiosError, type AxiosResponse } from 'axios';
import { ParticipantKind } from '../../../api/generated';
import { documentsApi, principalsApi } from '../../../api/config';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { DueDateDialog } from './DueDateDialog';
import { ParticipantsDialog } from './ParticipantsDialog';

vi.mock('../../../api/config', () => ({
  axiosInstance: { post: vi.fn(), get: vi.fn() },
  documentsApi: {
    listParticipants: vi.fn(),
    addParticipant: vi.fn(),
    removeParticipant: vi.fn(),
    updateDueDate: vi.fn(),
  },
  principalsApi: { searchPrincipals: vi.fn(), listTeamMembers: vi.fn() },
  reviewWorkflowApi: { getDocumentWorkflow: vi.fn(), transitionDocumentWorkflow: vi.fn() },
}));

const DOC_ID = '00000000-0000-0000-0000-0000000000d1';
const ME = '00000000-0000-0000-0000-0000000000aa';

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

/** The review hub's dialogs in German (issue #836): interpolated toasts and mapped conflicts. */
describe('review hub dialogs in German', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    await applyLocale('de');
    vi.mocked(documentsApi.listParticipants).mockResolvedValue({
      data: {
        participants: [
          { id: 'p1', kind: ParticipantKind.User, principalId: 'u-max', displayName: 'Max' },
        ],
      },
    } as Awaited<ReturnType<typeof documentsApi.listParticipants>>);
    vi.mocked(principalsApi.searchPrincipals).mockResolvedValue({
      data: {
        principals: [{ id: 'u-ava', kind: ParticipantKind.User, displayName: 'Avery Auditor' }],
      },
    } as Awaited<ReturnType<typeof principalsApi.searchPrincipals>>);
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  function renderParticipants(notify: (message: string, severity?: string) => void) {
    return render(
      <ParticipantsDialog
        documentId={DOC_ID}
        open
        onClose={vi.fn()}
        isOwner
        ownUserId={ME}
        notify={notify}
      />,
      { wrapper },
    );
  }

  it('renders the participants dialog and its add toast in German', async () => {
    vi.mocked(documentsApi.addParticipant).mockResolvedValue({ data: { id: 'p2' } } as Awaited<
      ReturnType<typeof documentsApi.addParticipant>
    >);
    const notify = vi.fn();
    renderParticipants(notify);

    expect(screen.getByText('Beteiligte')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Personen oder Teams hinzufügen…')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Max entfernen' })).toBeInTheDocument();
    expect(screen.getByText('Person')).toBeInTheDocument();

    fireEvent.click(await screen.findByTestId('add-principal-u-ava'));
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Avery Auditor hinzugefügt.'));
  });

  it('maps a duplicate-participant conflict to German text', async () => {
    const conflict = new AxiosError('conflict');
    conflict.response = {
      status: 409,
      data: { code: 'DUPLICATE_PARTICIPANT', message: 'server prose' },
    } as AxiosResponse;
    vi.mocked(documentsApi.addParticipant).mockRejectedValue(conflict);
    const notify = vi.fn();
    renderParticipants(notify);

    fireEvent.click(await screen.findByTestId('add-principal-u-ava'));
    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Ist bereits Reviewer dieses Dokuments.', 'error'),
    );
  });

  it('renders the due-date dialog with German copy and actions', () => {
    render(
      <DueDateDialog
        documentId={DOC_ID}
        open
        onClose={vi.fn()}
        dueAt="2026-12-24T22:59:59.999Z"
        notify={vi.fn()}
      />,
      { wrapper },
    );

    expect(screen.getByText('Fälligkeitsdatum des Reviews')).toBeInTheDocument();
    expect(screen.getByText(/reine Orientierungsfrist/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Leeren' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abbrechen' })).toBeInTheDocument();
  });
});
