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
import type { AnnotationView, CommentView } from '../../api/generated';
import { AnnotationStatus, PlacementStatus } from '../../api/generated';
import { hasNewComments, isNewAnnotation, isNewComment, isUnseen } from './newSince';

const SEEN = '2026-07-04T12:00:00Z';

const annotation = (overrides: Partial<AnnotationView> = {}): AnnotationView => ({
  id: 'a1',
  documentId: 'd1',
  authorId: 'other',
  status: AnnotationStatus.Open,
  placementStatus: PlacementStatus.Placed,
  commentCount: 1,
  createdAt: '2026-07-04T13:00:00Z',
  updatedAt: '2026-07-04T13:00:00Z',
  ...overrides,
});

const comment = (overrides: Partial<CommentView> = {}): CommentView => ({
  id: 'c1',
  annotationId: 'a1',
  authorId: 'other',
  body: 'hello',
  createdAt: '2026-07-04T13:00:00Z',
  ...overrides,
});

describe('isNewAnnotation', () => {
  it('marks a foreign annotation created after the previous visit', () => {
    expect(isNewAnnotation(annotation(), SEEN, 'me')).toBe(true);
    expect(isNewAnnotation(annotation({ createdAt: '2026-07-04T11:00:00Z' }), SEEN, 'me')).toBe(
      false,
    );
  });

  it('never marks own annotations or first visits', () => {
    expect(isNewAnnotation(annotation({ authorId: 'me' }), SEEN, 'me')).toBe(false);
    expect(isNewAnnotation(annotation(), null, 'me')).toBe(false);
  });
});

describe('hasNewComments', () => {
  it('relies on the foreign-activity timestamp', () => {
    expect(
      hasNewComments(annotation({ latestCommentFromOthersAt: '2026-07-04T13:00:00Z' }), SEEN),
    ).toBe(true);
    expect(
      hasNewComments(annotation({ latestCommentFromOthersAt: '2026-07-04T11:00:00Z' }), SEEN),
    ).toBe(false);
    expect(hasNewComments(annotation(), SEEN)).toBe(false);
    expect(
      hasNewComments(annotation({ latestCommentFromOthersAt: '2026-07-04T13:00:00Z' }), null),
    ).toBe(false);
  });
});

describe('isNewComment', () => {
  it('marks foreign comments after the previous visit, never own ones', () => {
    expect(isNewComment(comment(), SEEN, 'me')).toBe(true);
    expect(isNewComment(comment({ authorId: 'me' }), SEEN, 'me')).toBe(false);
    expect(isNewComment(comment({ createdAt: '2026-07-04T11:00:00Z' }), SEEN, 'me')).toBe(false);
    expect(isNewComment(comment(), null, 'me')).toBe(false);
  });
});

describe('isUnseen', () => {
  it('combines new annotations and new foreign comments', () => {
    expect(isUnseen(annotation(), SEEN, 'me')).toBe(true);
    expect(
      isUnseen(
        annotation({
          createdAt: '2026-07-04T11:00:00Z',
          latestCommentFromOthersAt: '2026-07-04T13:00:00Z',
        }),
        SEEN,
        'me',
      ),
    ).toBe(true);
    expect(isUnseen(annotation({ createdAt: '2026-07-04T11:00:00Z' }), SEEN, 'me')).toBe(false);
  });
});
