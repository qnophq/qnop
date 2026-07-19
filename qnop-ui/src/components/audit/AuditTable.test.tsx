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
  scope: 'DOCUMENT',
  eventType: 'workflow.transition',
  documentId: 'doc-1',
  documentTitle: 'Master services agreement',
  documentSlug: 'msa',
  actorId: 'actor-1',
  actorDisplayName: 'Avery Auditor',
  actorSlug: 'avery-auditor',
  detail: '{"from":"DRAFT","to":"IN_REVIEW"}',
  createdAt: '2026-07-01T10:00:00Z',
};

const systemEvent: AuditEvent = {
  id: 'e2',
  scope: 'DOCUMENT',
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
  const onFilterSystem = vi.fn();
  const onFilterDocument = vi.fn();
  renderWithProviders(
    <AuditTable
      events={events}
      onFilterActor={onFilterActor}
      onFilterSystem={onFilterSystem}
      onFilterDocument={onFilterDocument}
    />,
  );
  return { onFilterActor, onFilterSystem, onFilterDocument };
}

describe('AuditTable', () => {
  it('shows an empty state when there are no events', () => {
    renderTable([]);
    expect(screen.getByText('No audit events found.')).toBeInTheDocument();
  });

  it('renders a human event label and readable detail', () => {
    renderTable([userEvent]);
    expect(screen.getByText('Status changed')).toBeInTheDocument();
    expect(screen.getByText('Draft → In review')).toBeInTheDocument();
  });

  it('links the actor to their profile and the document to the review', () => {
    renderTable([userEvent]);
    expect(screen.getByRole('link', { name: "View Avery Auditor's profile" })).toHaveAttribute(
      'href',
      '/users/avery-auditor',
    );
    expect(screen.getByRole('link', { name: 'Master services agreement' })).toHaveAttribute(
      'href',
      '/reviews/msa',
    );
  });

  it('gives the system actor its own emblem and a system filter, but no profile link', () => {
    renderTable([systemEvent]);
    expect(screen.getByText('System')).toBeInTheDocument();
    // The system actor has no person profile, but it does get its own filter.
    expect(screen.queryByRole('link', { name: /profile/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Filter by System' })).toBeInTheDocument();
  });

  it('filters to system-only events from the system filter button', () => {
    const { onFilterSystem } = renderTable([systemEvent]);
    fireEvent.click(screen.getByRole('button', { name: 'Filter by System' }));
    expect(onFilterSystem).toHaveBeenCalledTimes(1);
  });

  it('filters by actor from the dedicated filter button, separate from the profile link', () => {
    const { onFilterActor } = renderTable([userEvent]);
    fireEvent.click(screen.getByRole('button', { name: 'Filter by Avery Auditor' }));
    expect(onFilterActor).toHaveBeenCalledWith('actor-1', 'Avery Auditor');
  });

  it('filters by document from the dedicated filter button, separate from the review link', () => {
    const { onFilterDocument } = renderTable([userEvent]);
    fireEvent.click(screen.getByRole('button', { name: 'Filter by Master services agreement' }));
    expect(onFilterDocument).toHaveBeenCalledWith('doc-1', 'Master services agreement');
  });
});
