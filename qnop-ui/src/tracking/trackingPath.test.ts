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
import { anonymizePath } from './trackingPath';

const DOC = '8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f';

describe('anonymizePath', () => {
  it('names the parameter the router filled in', () => {
    expect(anonymizePath(`/reviews/${DOC}`, { documentId: DOC })).toBe('/reviews/:documentId');
    expect(anonymizePath(`/reviews/${DOC}/tasks`, { documentId: DOC })).toBe(
      '/reviews/:documentId/tasks',
    );
  });

  it('anonymises a slug, which no shape check could catch', () => {
    // The reason params are the source of truth: "mia-member" is a person's name
    // and looks like an ordinary path segment.
    expect(anonymizePath('/users/mia-member', { userId: 'mia-member' })).toBe('/users/:userId');
  });

  it('still catches an id from a route it was told nothing about', () => {
    expect(anonymizePath(`/reviews/${DOC}/tasks`)).toBe('/reviews/:id/tasks');
    expect(anonymizePath('/documents/1234567')).toBe('/documents/:id');
  });

  it('replaces every segment of a splat', () => {
    expect(anonymizePath('/attachments/abc/def', { '*': 'abc/def' })).toBe(
      '/attachments/:path/:path',
    );
  });

  it('leaves ordinary pages readable', () => {
    // A report has to still say something: these are the rows an operator reads.
    expect(anonymizePath('/admin/settings')).toBe('/admin/settings');
    expect(anonymizePath('/reviews')).toBe('/reviews');
    expect(anonymizePath('/my-teams')).toBe('/my-teams');
    expect(anonymizePath('/')).toBe('/');
  });

  it('ignores empty params rather than replacing everything', () => {
    expect(anonymizePath('/reviews/new', { documentId: undefined })).toBe('/reviews/new');
    expect(anonymizePath('/reviews/new', { documentId: '' })).toBe('/reviews/new');
  });
});
