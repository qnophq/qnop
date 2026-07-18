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
import type { AuditEvent } from '../../api/generated';
import { renderWithProviders } from '../../test/renderWithProviders';
import { AuditTable } from './AuditTable';

const userEvent: AuditEvent = {
  id: 'e1',
  eventType: 'workflow.transition',
  documentId: 'doc-1',
  documentTitle: 'Master services agreement',
  actorId: 'actor-1',
  actorDisplayName: 'Avery Auditor',
  detail: '{"from":"DRAFT","to":"IN_REVIEW"}',
  createdAt: '2026-07-01T10:00:00Z',
};

const systemEvent: AuditEvent = {
  id: 'e2',
  eventType: 'extraction.failed',
  documentId: 'doc-2',
  documentTitle: 'Ingest report',
  actorId: undefined,
  actorDisplayName: 'System',
  detail: '{"reason":"BAD_PDF"}',
  createdAt: '2026-07-02T11:00:00Z',
};

function renderTable(events: AuditEvent[]) {
  const onFilterActor = vi.fn();
  const onFilterDocument = vi.fn();
  renderWithProviders(
    <AuditTable
      events={events}
      onFilterActor={onFilterActor}
      onFilterDocument={onFilterDocument}
    />,
  );
  return { onFilterActor, onFilterDocument };
}

describe('AuditTable', () => {
  it('shows an empty state when there are no events', () => {
    renderTable([]);
    expect(screen.getByText('No audit events found.')).toBeInTheDocument();
  });

  it('renders a human event label, readable detail and the resolved names', () => {
    renderTable([userEvent]);
    expect(screen.getByText('Status changed')).toBeInTheDocument();
    expect(screen.getByText('Draft → In review')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Avery Auditor' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Master services agreement' })).toBeInTheDocument();
  });

  it('renders the system actor as inert text, not a filter link', () => {
    renderTable([systemEvent]);
    expect(screen.getByText('System')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'System' })).not.toBeInTheDocument();
  });

  it('filters by actor when the actor is clicked', () => {
    const { onFilterActor } = renderTable([userEvent]);
    fireEvent.click(screen.getByRole('button', { name: 'Avery Auditor' }));
    expect(onFilterActor).toHaveBeenCalledWith('actor-1', 'Avery Auditor');
  });

  it('filters by document when the document is clicked', () => {
    const { onFilterDocument } = renderTable([userEvent]);
    fireEvent.click(screen.getByRole('button', { name: 'Master services agreement' }));
    expect(onFilterDocument).toHaveBeenCalledWith('doc-1', 'Master services agreement');
  });
});
