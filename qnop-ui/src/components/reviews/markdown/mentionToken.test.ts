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
import { activeMentionQuery, mentionToken } from './mentionToken';

describe('mentionToken (#462)', () => {
  it('builds the canonical id-based token with the @ inside the link text', () => {
    expect(mentionToken({ id: 'abc-123', name: 'Alice Smith' })).toBe(
      '[@Alice Smith](mention:abc-123)',
    );
  });

  it('detects an @query that starts the text or follows whitespace', () => {
    expect(activeMentionQuery('@Al', 3)).toEqual({ query: 'Al', start: 0 });
    expect(activeMentionQuery('hi @Bob', 7)).toEqual({ query: 'Bob', start: 3 });
    expect(activeMentionQuery('@', 1)).toEqual({ query: '', start: 0 });
  });

  it('does not trigger mid-word, after the query ends, or for an email', () => {
    expect(activeMentionQuery('a@b', 3)).toBeNull(); // @ mid-word (email-like)
    expect(activeMentionQuery('@Al done', 8)).toBeNull(); // caret past the query
    expect(activeMentionQuery('plain text', 10)).toBeNull();
  });
});
