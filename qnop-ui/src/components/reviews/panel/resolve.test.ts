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

import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import {
  dismissErrorKey,
  mayDismissAnnotation,
  mayReopenAnnotation,
  mayResolveAnnotation,
  reopenErrorKey,
  resolveErrorKey,
} from './resolve';

function conflict(code: string): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, {
    status: 409,
    data: { code },
  } as AxiosResponse);
}

const AUTHOR = 'author-1';
const OWNER = 'owner-1';
const OTHER = 'other-1';

const annotation = (status: AnnotationStatus): AnnotationView =>
  ({
    id: 'a1',
    documentId: 'd1',
    authorId: AUTHOR,
    status,
    firstComment: 'first',
    commentCount: 1,
    reactions: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
  }) as AnnotationView;

describe('mayResolveAnnotation', () => {
  it('lets only the author resolve, and only while OPEN', () => {
    expect(mayResolveAnnotation(annotation(AnnotationStatus.Open), AUTHOR)).toBe(true);
    expect(mayResolveAnnotation(annotation(AnnotationStatus.Open), OWNER)).toBe(false);
    expect(mayResolveAnnotation(annotation(AnnotationStatus.Resolved), AUTHOR)).toBe(false);
    expect(mayResolveAnnotation(annotation(AnnotationStatus.Dismissed), AUTHOR)).toBe(false);
  });
});

describe('mayDismissAnnotation (#408)', () => {
  it('offers dismiss to the owner and to admins on a foreign OPEN annotation', () => {
    const open = annotation(AnnotationStatus.Open);
    expect(mayDismissAnnotation(open, OWNER, OWNER, false)).toBe(true);
    expect(mayDismissAnnotation(open, OTHER, OWNER, true)).toBe(true);
  });

  it('never offers dismiss to the author — their path is Resolve', () => {
    const open = annotation(AnnotationStatus.Open);
    expect(mayDismissAnnotation(open, AUTHOR, OWNER, false)).toBe(false);
    expect(mayDismissAnnotation(open, AUTHOR, AUTHOR, true)).toBe(false);
  });

  it('refuses other reviewers, settled annotations and missing identity', () => {
    expect(mayDismissAnnotation(annotation(AnnotationStatus.Open), OTHER, OWNER, false)).toBe(
      false,
    );
    expect(mayDismissAnnotation(annotation(AnnotationStatus.Resolved), OWNER, OWNER, false)).toBe(
      false,
    );
    expect(mayDismissAnnotation(annotation(AnnotationStatus.Dismissed), OWNER, OWNER, false)).toBe(
      false,
    );
    expect(mayDismissAnnotation(annotation(AnnotationStatus.Open), null, OWNER, false)).toBe(false);
  });
});

describe('mayReopenAnnotation (#394/#408)', () => {
  it('lets the author reopen both settled states', () => {
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Resolved), AUTHOR)).toBe(true);
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Dismissed), AUTHOR)).toBe(true);
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Open), AUTHOR)).toBe(false);
  });

  it('lets an admin reopen a foreign settled annotation', () => {
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Resolved), OTHER, true)).toBe(true);
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Dismissed), OTHER, true)).toBe(true);
  });

  it('keeps the owner out — a dismissal is not theirs to reverse', () => {
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Dismissed), OWNER, false)).toBe(false);
    expect(mayReopenAnnotation(annotation(AnnotationStatus.Resolved), OWNER, false)).toBe(false);
  });
});

describe('conflict messages (#836)', () => {
  it('maps known resolve conflicts to catalog keys and anything else to the generic failure', () => {
    expect(resolveErrorKey(conflict('ANNOTATION_ALREADY_RESOLVED'))).toBe(
      'resolve.conflicts.ANNOTATION_ALREADY_RESOLVED',
    );
    expect(resolveErrorKey(conflict('SOMETHING_ELSE'))).toBe('resolve.failed');
    expect(resolveErrorKey(new Error('offline'))).toBe('resolve.failed');
  });

  it('maps known reopen conflicts to catalog keys', () => {
    expect(reopenErrorKey(conflict('REVIEW_CLOSED'))).toBe('reopen.conflicts.REVIEW_CLOSED');
    expect(reopenErrorKey(conflict('ANNOTATION_NOT_RESOLVED'))).toBe(
      'reopen.conflicts.ANNOTATION_NOT_RESOLVED',
    );
    expect(reopenErrorKey(undefined)).toBe('reopen.failed');
  });

  it('maps known dismiss conflicts to catalog keys', () => {
    expect(dismissErrorKey(conflict('ANNOTATION_NOT_OPEN'))).toBe(
      'dismiss.conflicts.ANNOTATION_NOT_OPEN',
    );
    expect(dismissErrorKey(conflict('REVIEW_CLOSED'))).toBe('dismiss.conflicts.REVIEW_CLOSED');
    expect(dismissErrorKey(conflict('ANNOTATION_ALREADY_RESOLVED'))).toBe('dismiss.failed');
  });
});
