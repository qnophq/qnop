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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import type { AuditEvent, AuditEventListResponse } from '../../api/generated';
import { axiosInstance } from '../../api/config';
import { renderWithProviders } from '../../test/renderWithProviders';
import { AuditPage } from './AuditPage';

/**
 * End-to-end wiring for the "Actor: System" filter (issue #466): renders the
 * real AuditPage — the real useAuditLog hook and the real generated client —
 * and only stubs the network at the shared axios instance. Reproduces the
 * user's action (click "Filter by System") and asserts the request the page
 * then fires actually carries `actorSystem=true`, so the whole
 * click → state → query → URL path is verified in one shot.
 */
const systemEvent: AuditEvent = {
  id: 'sys-1',
  scope: 'DOCUMENT',
  eventType: 'document.extraction.failed',
  documentId: 'doc-1',
  documentTitle: 'Ingest report',
  documentSlug: 'ingest-report',
  createdAt: '2026-07-01T10:00:00Z',
};

const userEvent: AuditEvent = {
  id: 'usr-1',
  scope: 'DOCUMENT',
  eventType: 'annotation.created',
  documentId: 'doc-2',
  documentTitle: 'Master services agreement',
  documentSlug: 'msa',
  actorId: 'user-1',
  actorDisplayName: 'Mia Member',
  actorSlug: 'mia-member',
  createdAt: '2026-07-01T09:00:00Z',
};

const FULL_PAGE: AuditEventListResponse = {
  items: [systemEvent, userEvent],
  total: 2,
  page: 0,
  size: 20,
};
const SYSTEM_ONLY_PAGE: AuditEventListResponse = {
  items: [systemEvent],
  total: 1,
  page: 0,
  size: 20,
};

function lastRequestUrl(spy: ReturnType<typeof vi.spyOn>): string {
  const arg = spy.mock.calls[spy.mock.calls.length - 1][0] as { url?: string };
  return arg.url ?? '';
}

describe('AuditPage — system filter (end to end)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('actually filters the list when "Filter by System" is clicked', async () => {
    // The stubbed server honours the filter: system-only when actorSystem=true.
    const request = vi
      .spyOn(axiosInstance, 'request')
      .mockImplementation(async (config: { url?: string }) => ({
        data: (config.url ?? '').includes('actorSystem=true') ? SYSTEM_ONLY_PAGE : FULL_PAGE,
      }));

    renderWithProviders(<AuditPage />);

    // Unfiltered: both a human actor and the system actor are visible.
    await screen.findByText('Mia Member');
    expect(screen.getByText('System')).toBeInTheDocument();
    expect(lastRequestUrl(request)).not.toContain('actorSystem=');

    fireEvent.click(screen.getByRole('button', { name: 'Filter by System' }));

    // The request carries the system filter…
    await waitFor(() => expect(lastRequestUrl(request)).toContain('actorSystem=true'));
    expect(lastRequestUrl(request)).not.toContain('actorId=');
    // …and the human-actor row is actually gone — the list really narrowed.
    await waitFor(() => expect(screen.queryByText('Mia Member')).not.toBeInTheDocument());
    expect(screen.getByText('System')).toBeInTheDocument();
    expect(screen.getByText('Actor: System')).toBeInTheDocument();
  });
});
