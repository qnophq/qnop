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

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, renderHook, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router';
import { buildTheme } from '../../../theme/theme';
import { useDocument } from '../../../api/hooks/useDocuments';
import { useParticipants } from '../../../api/hooks/useReviews';
import { useAuthStore } from '../../../stores/authStore';
import { registerMentionContributor, type MentionPrincipal } from '../../../extensions/mentions';
import { useMentionRoster } from './useMentionRoster';
import { useMentionNames } from './useMentionNames';
import { MentionLink } from './MentionLink';

vi.mock('../../../api/hooks/useDocuments', () => ({ useDocument: vi.fn() }));
vi.mock('../../../api/hooks/useReviews', () => ({ useParticipants: vi.fn() }));
vi.mock('../../../api/hooks/useUsers', () => ({ useUserProfile: vi.fn(() => ({ data: null })) }));

const TEAM: MentionPrincipal = {
  id: 'team-1',
  name: 'Platform Team',
  slug: 'platform-team',
  kind: 'team',
  href: '/teams/platform-team',
  hint: 'notifies 5 people',
};

/** The test-only fake contributor the acceptance criteria ask for (issue #598). */
function registerFakeContributor(principal: MentionPrincipal = TEAM): () => void {
  return registerMentionContributor({
    id: 'fake-teams',
    candidatesFor: (documentId) => (documentId === 'doc-1' ? [principal] : []),
    resolve: (slug) => (slug.toLowerCase() === principal.slug ? principal : undefined),
  });
}

let unregister: (() => void) | null = null;
afterEach(() => {
  unregister?.();
  unregister = null;
});

beforeEach(() => {
  useAuthStore.setState({ userId: 'me' });
  vi.mocked(useDocument).mockReturnValue({
    data: {
      anonymous: false,
      ownerId: 'owner-1',
      ownerDisplayName: 'Olivia Owner',
      ownerSlug: 'olivia-owner',
    },
  } as never);
  vi.mocked(useParticipants).mockReturnValue({ data: { participants: [] } } as never);
});

describe('mention seams with a contributed namespace (issue #598)', () => {
  it('appends contributed candidates to the roster, after the users', () => {
    unregister = registerFakeContributor();
    const { result } = renderHook(() => useMentionRoster('doc-1'));

    expect(result.current.map((candidate) => candidate.slug)).toEqual([
      'olivia-owner',
      'platform-team',
    ]);
    expect(result.current[1]).toMatchObject({ kind: 'team', hint: 'notifies 5 people' });
  });

  it('offers no contributed candidates in an anonymous review', () => {
    unregister = registerFakeContributor();
    vi.mocked(useDocument).mockReturnValue({ data: { anonymous: true } } as never);
    const { result } = renderHook(() => useMentionRoster('doc-1'));
    expect(result.current).toEqual([]);
  });

  it('keeps the roster byte-identical without a contributor', () => {
    const { result } = renderHook(() => useMentionRoster('doc-1'));
    expect(result.current.map((candidate) => candidate.slug)).toEqual(['olivia-owner']);
  });

  it('resolves a contributed slug in plain-text excerpts', () => {
    unregister = registerFakeContributor();
    const { result } = renderHook(() => useMentionNames('doc-1'));
    expect(result.current('ping @platform-team and @olivia-owner')).toBe(
      'ping Platform Team and Olivia Owner',
    );
  });

  it('renders the pill through the contributed resolver — name, link, no profile fetch', async () => {
    unregister = registerFakeContributor();
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>
          <MentionLink slug="platform-team">@platform-team</MentionLink>
        </MemoryRouter>
      </ThemeProvider>,
    );

    const pill = screen.getByTestId('mention-link');
    expect(pill).toHaveTextContent('Platform Team');
    expect(pill).toHaveAttribute('href', '/teams/platform-team');
    const { useUserProfile } = await import('../../../api/hooks/useUsers');
    // The user-profile query is disabled for a slug a contributor owns.
    await waitFor(() =>
      expect(vi.mocked(useUserProfile)).toHaveBeenCalledWith('platform-team', false),
    );
  });

  it('falls back to the raw @slug pill without a contributor', () => {
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>
          <MentionLink slug="platform-team">@platform-team</MentionLink>
        </MemoryRouter>
      </ThemeProvider>,
    );
    expect(screen.getByTestId('mention-link')).toHaveTextContent('@platform-team');
    expect(screen.getByTestId('mention-link')).toHaveAttribute('href', '/users/platform-team');
  });
});
