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
import { activeMentionQuery, mentionToken, replaceMentionTokens } from './mentionToken';

describe('mentionToken (#462)', () => {
  it('builds the GitHub-style slug token', () => {
    expect(mentionToken({ id: 'abc-123', name: 'Alice Smith', slug: 'alice-smith' })).toBe(
      '@alice-smith',
    );
  });

  it('keeps the query alive across slug hyphens', () => {
    expect(activeMentionQuery('hi @anna-kr', 11)).toEqual({ query: 'anna-kr', start: 3 });
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

describe('replaceMentionTokens (#462)', () => {
  const names = new Map([['ben-roth', 'Ben Roth']]);
  const resolve = (slug: string) => names.get(slug.toLowerCase());

  it('replaces resolved tokens and keeps the surrounding text intact', () => {
    expect(replaceMentionTokens('ping @ben-roth please', resolve)).toBe('ping Ben Roth please');
    expect(replaceMentionTokens('@ben-roth first', resolve)).toBe('Ben Roth first');
    expect(replaceMentionTokens('(@ben-roth)', resolve)).toBe('(Ben Roth)');
  });

  it('leaves unresolved tokens as the raw @slug', () => {
    expect(replaceMentionTokens('ping @ghost-user please', resolve)).toBe(
      'ping @ghost-user please',
    );
  });

  it('leaves email-like @ sequences alone', () => {
    expect(replaceMentionTokens('mail a@ben-roth.example', resolve)).toBe(
      'mail a@ben-roth.example',
    );
  });
});
