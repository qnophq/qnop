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

import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import type { AuditEvent } from '../../api/generated';
import { applyLocale } from '../../i18n';
import { renderWithProviders } from '../../test/renderWithProviders';
import { AuditTable } from './AuditTable';

const transition: AuditEvent = {
  id: 'e1',
  scope: 'DOCUMENT',
  eventType: 'workflow.transition',
  documentId: 'doc-1',
  documentTitle: 'Rahmenvertrag',
  documentSlug: 'rahmenvertrag',
  actorId: 'actor-1',
  actorDisplayName: 'Avery Auditor',
  actorSlug: 'avery-auditor',
  detail: '{"from":"DRAFT","to":"IN_REVIEW"}',
  createdAt: '2026-07-01T10:00:00Z',
};

const purge: AuditEvent = {
  id: 'e2',
  scope: 'SYSTEM',
  eventType: 'review.purged',
  detail:
    '{"reviews":2,"storageObjects":1,"purged":[{"id":"d1","title":"Q3 report"}],"truncated":1}',
  createdAt: '2026-07-02T11:00:00Z',
};

function renderTable() {
  renderWithProviders(
    <AuditTable
      events={[transition, purge]}
      onFilterActor={() => undefined}
      onFilterSystem={() => undefined}
      onFilterDocument={() => undefined}
    />,
  );
}

/** The audit table in German (issue #836): event labels, plural details, interpolated filters. */
describe('AuditTable in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('translates columns, event labels and the system actor', () => {
    renderTable();

    expect(screen.getByRole('columnheader', { name: 'Ereignis' })).toBeInTheDocument();
    expect(screen.getByText('Status geändert')).toBeInTheDocument();
    expect(screen.getByText('Reviews endgültig gelöscht')).toBeInTheDocument();
    expect(screen.getByText('System')).toBeInTheDocument();
  });

  it('renders details from translated parts, plurals included', () => {
    renderTable();

    expect(screen.getByText('Entwurf → Im Review')).toBeInTheDocument();
    expect(
      screen.getByText('2 Reviews · 1 Speicherobjekt · Q3 report +1 weitere'),
    ).toBeInTheDocument();
  });

  it('interpolates the filter buttons and follows a switch back to English', async () => {
    renderTable();
    expect(screen.getByRole('button', { name: 'Nach Avery Auditor filtern' })).toBeInTheDocument();

    await act(() => applyLocale('en'));

    expect(screen.getByRole('button', { name: 'Filter by Avery Auditor' })).toBeInTheDocument();
    expect(screen.getByText('Draft → In review')).toBeInTheDocument();
    expect(
      screen.getByText('2 reviews · 1 storage object · Q3 report +1 more'),
    ).toBeInTheDocument();
  });
});
