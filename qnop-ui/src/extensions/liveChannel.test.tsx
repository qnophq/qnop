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

import { describe, expect, it, vi, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  registerLiveChannelContributor,
  useLiveChannel,
  type LiveReviewContext,
} from './liveChannel';

const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

function wrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('live-channel slot (issue #602)', () => {
  it('mounts contributors with the context and tears them down on unmount', () => {
    const teardown = vi.fn();
    let seen: LiveReviewContext | null = null;
    cleanups.push(
      registerLiveChannelContributor({
        id: 'fake-sse',
        onReviewMounted: (context) => {
          seen = context;
          return teardown;
        },
      }),
    );
    const client = new QueryClient();
    const { unmount, rerender } = renderHook(({ id }) => useLiveChannel(id), {
      initialProps: { id: 'doc-1' },
      wrapper: wrapper(client),
    });

    expect(seen!.documentId).toBe('doc-1');
    rerender({ id: 'doc-2' });
    expect(teardown).toHaveBeenCalledTimes(1); // remount per review
    expect(seen!.documentId).toBe('doc-2');
    unmount();
    expect(teardown).toHaveBeenCalledTimes(2);
  });

  it('translates pushes into normal query invalidations', () => {
    let seen: LiveReviewContext | null = null;
    cleanups.push(
      registerLiveChannelContributor({
        id: 'fake-sse',
        onReviewMounted: (context) => {
          seen = context;
          return () => {};
        },
      }),
    );
    const client = new QueryClient();
    const spy = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useLiveChannel('doc-1'), { wrapper: wrapper(client) });

    seen!.invalidateAnnotations();
    expect(spy).toHaveBeenCalledWith({ queryKey: ['annotations', 'list', 'doc-1'] });
    seen!.invalidateComments('a1');
    expect(spy).toHaveBeenCalledWith({ queryKey: ['comments', 'list', 'a1'] });
  });

  it('a throwing contributor costs itself, never the surface', () => {
    cleanups.push(
      registerLiveChannelContributor({
        id: 'broken',
        onReviewMounted: () => {
          throw new Error('extension breakage');
        },
      }),
    );
    const healthy = vi.fn(() => () => {});
    cleanups.push(registerLiveChannelContributor({ id: 'healthy', onReviewMounted: healthy }));
    renderHook(() => useLiveChannel('doc-1'), { wrapper: wrapper(new QueryClient()) });
    expect(healthy).toHaveBeenCalledTimes(1);
  });
});
