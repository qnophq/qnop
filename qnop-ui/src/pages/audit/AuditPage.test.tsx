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
import { fireEvent, screen, within } from '@testing-library/react';
import type { AuditEvent, AuditEventListResponse } from '../../api/generated';
import { renderWithProviders } from '../../test/renderWithProviders';
import { AuditPage } from './AuditPage';

const { queryState, hookCalls } = vi.hoisted(() => ({
  queryState: {
    data: undefined as AuditEventListResponse | undefined,
    isLoading: false,
    isFetching: false,
    isError: false,
  },
  hookCalls: [] as Record<string, unknown>[],
}));

vi.mock('../../api/hooks/useAuditLog', () => ({
  useAuditLog: (params: Record<string, unknown>) => {
    hookCalls.push(params);
    return queryState;
  },
}));

// Stub the presentational table down to its inputs — the PAGE's own filter and
// pagination orchestration is what this test exercises (the table has its own).
vi.mock('../../components/audit/AuditTable', () => ({
  AuditTable: ({
    events,
    onFilterActor,
    onFilterSystem,
    onFilterDocument,
  }: {
    events: AuditEvent[];
    onFilterActor: (id: string, label: string) => void;
    onFilterSystem: () => void;
    onFilterDocument: (id: string, label: string) => void;
  }) => (
    <div data-testid="audit-table">
      <span data-testid="event-count">{events.length}</span>
      <button onClick={() => onFilterActor('actor-1', 'Avery Auditor')}>stub-filter-actor</button>
      <button onClick={onFilterSystem}>stub-filter-system</button>
      <button onClick={() => onFilterDocument('doc-1', 'MSA')}>stub-filter-document</button>
    </div>
  ),
}));

function page(overrides: Partial<AuditEventListResponse>): AuditEventListResponse {
  return { items: [], total: 0, page: 0, size: 20, ...overrides };
}

const event: AuditEvent = {
  id: 'e1',
  eventType: 'annotation.created',
  documentId: 'doc-1',
  createdAt: '2026-07-01T10:00:00Z',
};

const lastCall = () => hookCalls[hookCalls.length - 1];

beforeEach(() => {
  queryState.data = undefined;
  queryState.isLoading = false;
  queryState.isFetching = false;
  queryState.isError = false;
  hookCalls.length = 0;
});

describe('AuditPage', () => {
  it('renders the page header', () => {
    renderWithProviders(<AuditPage />);
    expect(screen.getByRole('heading', { name: 'Audit trail' })).toBeInTheDocument();
  });

  it('reveals a plain-language event guide on demand', () => {
    renderWithProviders(<AuditPage />);
    // Collapsed by default: the guide's description text is not mounted.
    expect(
      screen.queryByText('The review moved to a new workflow status (e.g. Draft → In review).'),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'What do these events mean?' }));

    expect(
      screen.getByText('The review moved to a new workflow status (e.g. Draft → In review).'),
    ).toBeInTheDocument();
  });

  it('shows a loading placeholder while the first page loads', () => {
    queryState.isLoading = true;
    renderWithProviders(<AuditPage />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('shows an error alert when the list fails', () => {
    queryState.isError = true;
    renderWithProviders(<AuditPage />);
    expect(screen.getByText('The audit trail could not be loaded.')).toBeInTheDocument();
  });

  it('renders the events returned by the query', () => {
    queryState.data = page({ items: [event, { ...event, id: 'e2' }], total: 2 });
    renderWithProviders(<AuditPage />);
    expect(screen.getByTestId('event-count')).toHaveTextContent('2');
  });

  it('filters by event type and resets to the first page', () => {
    queryState.data = page({ items: [event], total: 1 });
    renderWithProviders(<AuditPage />);

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Event type' }));
    fireEvent.click(screen.getByRole('option', { name: 'Status changed' }));

    expect(lastCall().eventType).toBe('workflow.transition');
    expect(lastCall().page).toBe(0);
  });

  it('passes a from-date filter as an ISO instant', () => {
    renderWithProviders(<AuditPage />);
    fireEvent.change(screen.getByLabelText('From'), {
      target: { value: '2026-03-01T09:30' },
    });
    expect(String(lastCall().from)).toContain('2026-03-01');
  });

  it('filters by actor from a row click and shows a removable chip', () => {
    queryState.data = page({ items: [event], total: 1 });
    renderWithProviders(<AuditPage />);

    fireEvent.click(screen.getByText('stub-filter-actor'));

    expect(lastCall().actorId).toBe('actor-1');
    const chip = screen.getByText('Actor: Avery Auditor');
    expect(chip).toBeInTheDocument();
  });

  it('filters to system events and shows a System chip', () => {
    queryState.data = page({ items: [event], total: 1 });
    renderWithProviders(<AuditPage />);

    fireEvent.click(screen.getByText('stub-filter-system'));

    expect(lastCall().actorSystem).toBe(true);
    expect(lastCall().actorId).toBeUndefined();
    expect(screen.getByText('Actor: System')).toBeInTheDocument();
  });

  it('filters by document from a row click', () => {
    queryState.data = page({ items: [event], total: 1 });
    renderWithProviders(<AuditPage />);

    fireEvent.click(screen.getByText('stub-filter-document'));

    expect(lastCall().documentId).toBe('doc-1');
    expect(screen.getByText('Document: MSA')).toBeInTheDocument();
  });

  it('clears an active filter chip', () => {
    queryState.data = page({ items: [event], total: 1 });
    renderWithProviders(<AuditPage />);
    fireEvent.click(screen.getByText('stub-filter-actor'));

    const chip = screen.getByText('Actor: Avery Auditor').closest('.MuiChip-root') as HTMLElement;
    fireEvent.click(within(chip).getByTestId('CancelIcon'));

    expect(screen.queryByText('Actor: Avery Auditor')).not.toBeInTheDocument();
    expect(lastCall().actorId).toBeUndefined();
  });

  it('pages through the results', () => {
    queryState.data = page({ items: [event], total: 100 });
    renderWithProviders(<AuditPage />);

    fireEvent.click(screen.getByRole('button', { name: 'Go to next page' }));

    expect(lastCall().page).toBe(1);
  });
});
