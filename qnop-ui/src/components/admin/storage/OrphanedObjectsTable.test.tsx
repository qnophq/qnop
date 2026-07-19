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

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import type { OrphanedObject } from '../../../api/generated';
import { renderWithProviders } from '../../../test/renderWithProviders';
import { OrphanedObjectsTable } from './OrphanedObjectsTable';

const OBJECTS: OrphanedObject[] = [
  { storageKey: 'sha256/ab/orphan-one', size: 1024, lastModified: '2026-07-01T10:00:00Z' },
  {
    storageKey: 'sha256/cd/orphan-two',
    size: 5 * 1024 * 1024,
    lastModified: '2026-07-02T10:00:00Z',
  },
];

function render(objects: OrphanedObject[], selected = new Set<string>(), busy = false) {
  const onToggle = vi.fn();
  const onToggleAll = vi.fn();
  const onDeleteOne = vi.fn();
  renderWithProviders(
    <OrphanedObjectsTable
      objects={objects}
      selected={selected}
      onToggle={onToggle}
      onToggleAll={onToggleAll}
      onDeleteOne={onDeleteOne}
      busy={busy}
    />,
  );
  return { onToggle, onToggleAll, onDeleteOne };
}

describe('OrphanedObjectsTable', () => {
  it('shows an empty state when there are no orphans', () => {
    render([]);
    expect(screen.getByText('No orphaned objects.')).toBeInTheDocument();
  });

  it('renders human-readable sizes', () => {
    render(OBJECTS);
    expect(screen.getByText('1 KB')).toBeInTheDocument();
    expect(screen.getByText('5 MB')).toBeInTheDocument();
  });

  it('toggles a single row and select-all', () => {
    const { onToggle, onToggleAll } = render(OBJECTS);

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select sha256/ab/orphan-one' }));
    expect(onToggle).toHaveBeenCalledWith('sha256/ab/orphan-one');

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select all orphaned objects' }));
    expect(onToggleAll).toHaveBeenCalledTimes(1);
  });

  it('deletes a single object from its row action', () => {
    const { onDeleteOne } = render(OBJECTS);
    fireEvent.click(screen.getByRole('button', { name: 'Delete sha256/cd/orphan-two' }));
    expect(onDeleteOne).toHaveBeenCalledWith('sha256/cd/orphan-two');
  });

  it('locks the checkboxes and delete actions while busy', () => {
    render(OBJECTS, new Set(), true);
    expect(screen.getByRole('checkbox', { name: 'Select all orphaned objects' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete sha256/ab/orphan-one' })).toBeDisabled();
  });
});
