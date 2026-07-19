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
import { fireEvent, screen } from '@testing-library/react';
import type { StorageConsistencyReport } from '../../api/generated';
import { renderWithProviders } from '../../test/renderWithProviders';
import { StorageConsistencyPage } from './StorageConsistencyPage';

const { queryState, deleteMutate } = vi.hoisted(() => ({
  queryState: {
    data: undefined as StorageConsistencyReport | undefined,
    isLoading: false,
    isFetching: false,
    isError: false,
    error: undefined as unknown,
    refetch: vi.fn(),
  },
  deleteMutate: vi.fn(),
}));

vi.mock('../../api/hooks/useStorageConsistency', () => ({
  useStorageConsistency: () => queryState,
  useDeleteOrphans: () => ({ mutate: deleteMutate, isPending: false }),
}));

vi.mock('../../components/admin/storage/OrphanedObjectsTable', () => ({
  OrphanedObjectsTable: ({
    objects,
    onToggleAll,
    onDeleteOne,
  }: {
    objects: { storageKey: string }[];
    onToggleAll: () => void;
    onDeleteOne: (key: string) => void;
  }) => (
    <div data-testid="orphan-table">
      <span data-testid="orphan-count">{objects.length}</span>
      <button onClick={onToggleAll}>toggle-all</button>
      <button onClick={() => onDeleteOne(objects[0].storageKey)}>delete-first</button>
    </div>
  ),
}));

vi.mock('../../components/admin/storage/MissingBinariesTable', () => ({
  MissingBinariesTable: ({ binaries }: { binaries: unknown[] }) => (
    <span data-testid="missing-count">{binaries.length}</span>
  ),
}));

function report(overrides: Partial<StorageConsistencyReport['summary']>): StorageConsistencyReport {
  return {
    summary: {
      dbReferencedCount: 10,
      storageObjectCount: 8,
      missingCount: 0,
      orphanedCount: 0,
      scannedAt: '2026-07-19T10:00:00Z',
      ...overrides,
    },
    missing: [],
    orphaned: [],
  };
}

const orphan = (key: string) => ({
  storageKey: key,
  size: 1024,
  lastModified: '2026-07-01T10:00:00Z',
});

beforeEach(() => {
  queryState.data = undefined;
  queryState.isLoading = false;
  queryState.isFetching = false;
  queryState.isError = false;
  queryState.error = undefined;
  deleteMutate.mockReset();
});

describe('StorageConsistencyPage', () => {
  it('renders the header', () => {
    renderWithProviders(<StorageConsistencyPage />);
    expect(screen.getByRole('heading', { name: 'Storage consistency' })).toBeInTheDocument();
  });

  it('shows a scanning placeholder while loading', () => {
    queryState.isLoading = true;
    renderWithProviders(<StorageConsistencyPage />);
    expect(screen.getByText('Scanning…')).toBeInTheDocument();
  });

  it('surfaces the scan-limit circuit breaker (409) as a warning', () => {
    queryState.isError = true;
    queryState.error = { response: { status: 409 } };
    renderWithProviders(<StorageConsistencyPage />);
    expect(screen.getByText(/scan was stopped/i)).toBeInTheDocument();
  });

  it('shows a generic error for a non-409 failure', () => {
    queryState.isError = true;
    queryState.error = { response: { status: 500 } };
    renderWithProviders(<StorageConsistencyPage />);
    expect(screen.getByText('The storage-consistency scan could not be run.')).toBeInTheDocument();
  });

  it('shows the all-clear when nothing is missing or orphaned', () => {
    queryState.data = report({ missingCount: 0, orphanedCount: 0 });
    renderWithProviders(<StorageConsistencyPage />);
    expect(screen.getByText('Everything reconciles')).toBeInTheDocument();
  });

  it('renders both finding tables when there are findings', () => {
    queryState.data = {
      ...report({ missingCount: 1, orphanedCount: 2 }),
      missing: [{ storageKey: 'm1', kind: 'VERSION', documentId: 'd1' }],
      orphaned: [orphan('o1'), orphan('o2')],
    };
    renderWithProviders(<StorageConsistencyPage />);
    expect(screen.getByTestId('missing-count')).toHaveTextContent('1');
    expect(screen.getByTestId('orphan-count')).toHaveTextContent('2');
  });

  it('bulk-deletes the selected orphans after confirmation', () => {
    queryState.data = {
      ...report({ orphanedCount: 2 }),
      orphaned: [orphan('o1'), orphan('o2')],
    };
    renderWithProviders(<StorageConsistencyPage />);

    fireEvent.click(screen.getByText('toggle-all'));
    fireEvent.click(screen.getByRole('button', { name: /Delete selected \(2\)/ }));
    // Confirm dialog opens; confirm.
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

    expect(deleteMutate).toHaveBeenCalledTimes(1);
    expect(deleteMutate.mock.calls[0][0]).toEqual(expect.arrayContaining(['o1', 'o2']));
  });

  it('deletes a single orphan from its row action', () => {
    queryState.data = {
      ...report({ orphanedCount: 1 }),
      orphaned: [orphan('only')],
    };
    renderWithProviders(<StorageConsistencyPage />);

    fireEvent.click(screen.getByText('delete-first'));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

    expect(deleteMutate).toHaveBeenCalledTimes(1);
    expect(deleteMutate.mock.calls[0][0]).toEqual(['only']);
  });
});
