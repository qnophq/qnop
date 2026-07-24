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
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { ParticipantKind } from '../../../api/generated';
import { useDocument } from '../../../api/hooks/useDocuments';
import { useParticipants } from '../../../api/hooks/useReviews';
import { useAuthStore } from '../../../stores/authStore';
import { buildTheme } from '../../../theme/theme';
import { NewTaskDialog } from './NewTaskDialog';

vi.mock('../../../api/hooks/useDocuments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/hooks/useDocuments')>()),
  useDocument: vi.fn(),
}));
vi.mock('../../../api/hooks/useReviews', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/hooks/useReviews')>()),
  useParticipants: vi.fn(),
}));

const ALICE_ID = '018f5a3e-0000-7000-8000-000000000001';
const OWNER_ID = '018f5a3e-0000-7000-8000-00000000000f';

function mockRoster(anonymous: boolean, overrides: { ownerId?: string } = {}) {
  vi.mocked(useDocument).mockReturnValue({
    data: {
      id: 'd1',
      title: 'Doc',
      anonymous,
      ownerId: overrides.ownerId ?? OWNER_ID,
      ownerDisplayName: overrides.ownerId ? 'Alice' : 'Olivia Owner',
      ownerSlug: overrides.ownerId ? 'alice-smith' : 'olivia-owner',
    },
  } as unknown as ReturnType<typeof useDocument>);
  vi.mocked(useParticipants).mockReturnValue({
    data: {
      participants: [
        {
          id: 'p1',
          kind: ParticipantKind.User,
          principalId: ALICE_ID,
          displayName: 'Alice',
          slug: 'alice-smith',
        },
      ],
    },
  } as unknown as ReturnType<typeof useParticipants>);
}

function renderDialog() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider theme={buildTheme('light')}>
        <NewTaskDialog open documentId="d1" versionNumber={1} notify={vi.fn()} onClose={vi.fn()} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

function typeMentionQuery(query = '@Al') {
  const ta = screen.getByLabelText('Annotation comment') as HTMLTextAreaElement;
  ta.focus();
  fireEvent.change(ta, { target: { value: query } });
  ta.setSelectionRange(query.length, query.length);
  fireEvent.keyUp(ta, { key: query.slice(-1) });
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ userId: null });
});

// Issue #462 follow-up: mentions must work when creating a global annotation,
// not only in comment threads — the dialog wires the roster itself.
describe('NewTaskDialog mentions', () => {
  it('offers the review roster on @', () => {
    mockRoster(false);
    renderDialog();

    typeMentionQuery();
    expect(screen.getByText('Alice')).toBeInTheDocument();
  });

  it('offers no picker in an anonymous review', () => {
    mockRoster(true);
    renderDialog();

    typeMentionQuery();
    expect(screen.queryByText('Alice')).not.toBeInTheDocument();
  });

  it('offers the review owner even though they are no participant', () => {
    mockRoster(false);
    renderDialog();

    typeMentionQuery('@Ol');
    expect(screen.getByText('Olivia Owner')).toBeInTheDocument();
  });

  it('does not duplicate an owner who also reviews', () => {
    mockRoster(false, { ownerId: ALICE_ID });
    renderDialog();

    typeMentionQuery();
    expect(screen.getAllByText('Alice')).toHaveLength(1);
  });

  it('never offers the caller themselves — neither as participant nor as owner', () => {
    useAuthStore.setState({ userId: ALICE_ID });
    mockRoster(false);
    renderDialog();

    typeMentionQuery();
    expect(screen.queryByText('Alice')).not.toBeInTheDocument();

    useAuthStore.setState({ userId: OWNER_ID });
    typeMentionQuery('@Ol');
    expect(screen.queryByText('Olivia Owner')).not.toBeInTheDocument();
  });
});
