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

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { NotificationDetail } from '../../api/generated';
import { applyLocale } from '../../i18n';
import { useNotificationText } from './notificationText';

function detail(overrides: Partial<NotificationDetail>): NotificationDetail {
  return {
    id: 'n1',
    type: 'MENTION',
    title: 'server title',
    body: 'server body',
    actorName: 'Ada Admin',
    documentTitle: 'NDA Acme Corp',
    accessible: true,
    actionTarget: 'ANNOTATION',
    createdAt: '2026-07-01T10:00:00Z',
    ...overrides,
  } as NotificationDetail;
}

function words(n: NotificationDetail) {
  const { result } = renderHook(() => useNotificationText());
  return {
    title: result.current.title(n),
    body: result.current.body(n),
    action: result.current.actionLabel(n),
  };
}

/** The client-side wording of notifications (issue #836). */
describe('useNotificationText', () => {
  afterEach(async () => {
    await act(() => applyLocale('en'));
  });

  it('words every type in English exactly as the server renders it', () => {
    expect(words(detail({ type: 'MENTION' }))).toEqual({
      title: 'Ada Admin mentioned you',
      body: 'Ada Admin mentioned you in a comment on “NDA Acme Corp”.',
      action: 'Open annotation',
    });
    expect(words(detail({ type: 'PARTICIPANT_ADDED', actionTarget: 'REVIEW' }))).toEqual({
      title: 'You were added to a review',
      body: 'Ada Admin added you as a reviewer on “NDA Acme Corp”.',
      action: 'Open review',
    });
    expect(words(detail({ type: 'ANNOTATION_DECIDED', decision: 'dismissed' })).body).toBe(
      'Ada Admin dismissed an annotation on “NDA Acme Corp”.',
    );
    expect(words(detail({ type: 'VERSION_UPLOADED', versionNumber: 3 })).body).toBe(
      'Ada Admin uploaded version 3 of “NDA Acme Corp”.',
    );
    expect(
      words(detail({ type: 'WORKFLOW_CHANGED', fromState: 'DRAFT', toState: 'CHANGES_REQUESTED' })),
    ).toMatchObject({
      title: 'The review moved to Changes requested',
      body: '“NDA Acme Corp” moved from Draft to Changes requested.',
    });
  });

  it('matches the server for the remaining types and states', () => {
    expect(words(detail({ type: 'ANNOTATION_CREATED' }))).toMatchObject({
      title: 'Ada Admin raised an annotation',
      body: 'Ada Admin raised a new annotation on “NDA Acme Corp”.',
    });
    expect(words(detail({ type: 'COMMENT_ADDED' }))).toMatchObject({
      title: 'Ada Admin replied',
      body: 'Ada Admin replied to a discussion on “NDA Acme Corp”.',
    });
    expect(words(detail({ type: 'ANNOTATION_DECIDED', decision: 'resolved' }))).toMatchObject({
      title: 'An annotation was resolved',
      body: 'Ada Admin resolved an annotation on “NDA Acme Corp”.',
    });
    expect(words(detail({ type: 'ANNOTATION_DECIDED', decision: 'reopened' })).title).toBe(
      'An annotation was reopened',
    );
    // No decision recorded reads "decided", as on the server.
    expect(words(detail({ type: 'ANNOTATION_DECIDED' }))).toMatchObject({
      title: 'An annotation was decided',
      body: 'Ada Admin decided an annotation on “NDA Acme Corp”.',
    });
    expect(words(detail({ type: 'COMMENT_ADDED', accessible: false }))).toMatchObject({
      title: 'A review you no longer have access to',
      body: 'This notification refers to a review that is no longer available to you.',
    });
    expect(
      words(detail({ type: 'REVIEW_DELETED', accessible: false, documentTitle: undefined })),
    ).toMatchObject({
      title: 'A review was deleted',
      body: '“a review” was permanently deleted by an administrator.',
    });
    expect(
      words(detail({ type: 'REVIEW_DELETED', accessible: false, documentTitle: 'Old NDA' })).body,
    ).toBe('“Old NDA” was permanently deleted by an administrator.');
  });

  it('words them in German', async () => {
    await act(() => applyLocale('de'));
    expect(words(detail({ type: 'COMMENT_ADDED' }))).toEqual({
      title: 'Ada Admin hat geantwortet',
      body: 'Ada Admin hat in einer Diskussion zu „NDA Acme Corp“ geantwortet.',
      action: 'Anmerkung öffnen',
    });
    expect(
      words(detail({ type: 'WORKFLOW_CHANGED', fromState: 'IN_REVIEW', toState: 'FINALIZED' })),
    ).toMatchObject({
      title: 'Das Review ist jetzt „Abgeschlossen“',
      body: '„NDA Acme Corp“ ist von „Im Review“ zu „Abgeschlossen“ gewechselt.',
    });
  });

  it('words a tombstone and a deleted review without facts', async () => {
    await act(() => applyLocale('de'));
    expect(
      words(detail({ type: 'COMMENT_ADDED', accessible: false, actionTarget: undefined })),
    ).toMatchObject({
      title: 'Ein Review, auf das du keinen Zugriff mehr hast',
      body: 'Diese Benachrichtigung bezieht sich auf ein Review, das für dich nicht mehr verfügbar ist.',
    });
    expect(
      words(detail({ type: 'REVIEW_DELETED', accessible: false, documentTitle: undefined })),
    ).toMatchObject({
      title: 'Ein Review wurde gelöscht',
      body: '„ein Review“ wurde von einem Administrator endgültig gelöscht.',
    });
  });

  it('keeps the server text for what it cannot word itself', async () => {
    await act(() => applyLocale('de'));
    // An unknown decision, a transition without its target, a missing actor, an unknown type.
    expect(words(detail({ type: 'ANNOTATION_DECIDED', decision: 'escalated' }))).toMatchObject({
      title: 'server title',
      body: 'server body',
    });
    expect(words(detail({ type: 'WORKFLOW_CHANGED', toState: undefined })).title).toBe(
      'server title',
    );
    expect(words(detail({ type: 'MENTION', actorName: undefined })).body).toBe('server body');
    // A headline that does not name the actor still translates without one.
    expect(words(detail({ type: 'VERSION_UPLOADED', actorName: undefined })).title).toBe(
      'Neue Version hochgeladen',
    );
    expect(words(detail({ type: 'VERSION_UPLOADED', versionNumber: undefined })).body).toBe(
      'server body',
    );
    // An edition-specific state the catalog does not know is humanized, not dropped.
    expect(
      words(detail({ type: 'WORKFLOW_CHANGED', fromState: 'DRAFT', toState: 'LEGAL_HOLD' })).title,
    ).toBe('Das Review ist jetzt „Legal hold“');
    expect(
      words(detail({ type: 'ANNOTATION_DECIDED', decision: undefined, actionTarget: undefined })),
    ).toMatchObject({
      title: 'Über eine Anmerkung wurde entschieden',
      action: 'Review öffnen',
    });
    expect(
      words(
        detail({ type: 'SOMETHING_NEW' as NotificationDetail['type'], actionTarget: undefined }),
      ),
    ).toEqual({ title: 'server title', body: 'server body', action: 'Review öffnen' });
  });

  it('renders names and titles as text, never as markup', () => {
    const n = detail({ actorName: '<b>Eve</b>', documentTitle: '{{actor}} <i>x</i>' });
    expect(words(n).body).toBe('<b>Eve</b> mentioned you in a comment on “{{actor}} <i>x</i>”.');
  });
});
