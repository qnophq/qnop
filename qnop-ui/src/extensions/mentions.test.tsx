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

import { describe, expect, it, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  registerMentionContributor,
  notifyMentionContributionsChanged,
  resolveContributedMention,
  useMentionContributors,
  type MentionContributor,
  type MentionPrincipal,
} from './mentions';

const TEAM: MentionPrincipal = {
  id: 't1',
  name: 'Platform Team',
  slug: 'platform-team',
  kind: 'team',
  hint: 'notifies 5 people',
};

function contributor(overrides: Partial<MentionContributor> = {}): MentionContributor {
  return {
    id: 'test-teams',
    candidatesFor: () => [TEAM],
    resolve: (slug) => (slug.toLowerCase() === TEAM.slug ? TEAM : undefined),
    ...overrides,
  };
}

const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

describe('mention extension registry (issue #598)', () => {
  it('exposes registered contributors to subscribed components and removes them again', () => {
    const { result } = renderHook(() => useMentionContributors());
    expect(result.current).toHaveLength(0);

    let unregister: () => void;
    act(() => {
      unregister = registerMentionContributor(contributor());
    });
    expect(result.current).toHaveLength(1);

    act(() => unregister());
    expect(result.current).toHaveLength(0);
  });

  it('re-renders subscribers when a contributor announces new data', () => {
    const { result } = renderHook(() => useMentionContributors());
    act(() => {
      cleanups.push(registerMentionContributor(contributor()));
    });
    const before = result.current;
    act(() => notifyMentionContributionsChanged());
    // A fresh snapshot: useSyncExternalStore consumers re-read candidatesFor/resolve.
    expect(result.current).not.toBe(before);
    expect(result.current).toHaveLength(1);
  });

  it('resolves a slug through the first owning contributor, case-insensitively', () => {
    const silent = contributor({ id: 'other', resolve: () => undefined, candidatesFor: () => [] });
    expect(resolveContributedMention([silent, contributor()], 'Platform-Team')).toEqual(TEAM);
    expect(resolveContributedMention([silent], 'platform-team')).toBeUndefined();
  });
});
