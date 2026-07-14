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
import { realAuthorId } from './realAuthorId';

const SELF = 'self-id';
const OWNER = 'owner-id';
const OTHER = 'other-id';
const TOKEN = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'; // pseudonym tokens LOOK like ids

describe('realAuthorId — the hover-card anonymity gate (issue #482)', () => {
  it('exposes any author of a non-anonymous review', () => {
    expect(realAuthorId({ anonymous: false, ownerId: OWNER }, SELF, OTHER)).toBe(OTHER);
  });

  it('suppresses foreign authors of an anonymous review — tokens look like ids', () => {
    expect(realAuthorId({ anonymous: true, ownerId: OWNER }, SELF, TOKEN)).toBeNull();
    expect(realAuthorId({ anonymous: true, ownerId: OWNER }, SELF, OTHER)).toBeNull();
  });

  it('keeps yourself and the structurally public owner in an anonymous review', () => {
    expect(realAuthorId({ anonymous: true, ownerId: OWNER }, SELF, SELF)).toBe(SELF);
    expect(realAuthorId({ anonymous: true, ownerId: OWNER }, SELF, OWNER)).toBe(OWNER);
  });

  it('exposes nothing without a review context or author', () => {
    expect(realAuthorId(undefined, SELF, OTHER)).toBeNull();
    expect(realAuthorId({ anonymous: false }, SELF, null)).toBeNull();
    expect(realAuthorId({ anonymous: false }, SELF, undefined)).toBeNull();
  });
});
