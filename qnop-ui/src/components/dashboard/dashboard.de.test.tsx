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
import type { DashboardActivity, DocumentSummary } from '../../api/generated';
import { applyLocale } from '../../i18n';
import { renderWithProviders } from '../../test/renderWithProviders';
import { ActivityCard } from './ActivityCard';
import { DeadlinesCard } from './DeadlinesCard';

const DAY_MS = 86_400_000;

const activity: DashboardActivity = {
  type: 'annotation.created',
  documentId: 'd1',
  documentTitle: 'Rahmenvertrag',
  documentSlug: 'rahmenvertrag',
  actorId: 'u1',
  actorSlug: 'anna-krause',
  actorDisplayName: 'Anna Krause',
  createdAt: new Date().toISOString(),
};

function overdueReview(days: number): DocumentSummary {
  return {
    id: 'r1',
    title: 'Rahmenvertrag',
    ownerId: 'someone',
    workflowState: 'IN_REVIEW',
    latestVersionNumber: 1,
    annotationCount: 0,
    openAnnotationCount: 0,
    participants: [],
    dueAt: new Date(Date.now() - days * DAY_MS).toISOString(),
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
  } as DocumentSummary;
}

/** The dashboard cards in German (issue #836): the activity sentence and the urgency plurals. */
describe('dashboard cards in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('builds the activity sentence around the linked actor and review', () => {
    renderWithProviders(<ActivityCard activity={[activity]} />);

    expect(screen.getByText('Letzte Aktivitäten')).toBeInTheDocument();
    const review = screen.getByRole('link', { name: 'Rahmenvertrag' });
    expect(review.getAttribute('href')).toBe('/reviews/rahmenvertrag');
    expect(review.parentElement).toHaveTextContent(
      /^Anna Krause hat in Rahmenvertrag eine Anmerkung erstellt · /,
    );
  });

  it('keeps the English sentence byte-identical after a switch back', async () => {
    renderWithProviders(<ActivityCard activity={[activity]} />);

    await act(() => applyLocale('en'));

    expect(screen.getByRole('link', { name: 'Rahmenvertrag' }).parentElement).toHaveTextContent(
      /^Anna Krause opened an annotation in Rahmenvertrag · /,
    );
  });

  it('pluralises the overdue label', () => {
    renderWithProviders(<DeadlinesCard reviews={[overdueReview(3)]} />);

    expect(screen.getByText('Fristen')).toBeInTheDocument();
    expect(screen.getByText('Seit 3 Tagen überfällig')).toBeInTheDocument();
  });

  it('uses the singular for one day', () => {
    renderWithProviders(<DeadlinesCard reviews={[overdueReview(1)]} />);

    expect(screen.getByText('Seit 1 Tag überfällig')).toBeInTheDocument();
  });
});
