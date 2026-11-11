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

import { render, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerConfigTracking } from '../api/generated';
import { trackEvent } from './trackEvent';
import { TrackingBoot } from './TrackingBoot';

const state = vi.hoisted(() => ({
  tracking: undefined as ServerConfigTracking | undefined,
  optOut: 'false',
}));

vi.mock('../api/hooks/useConfig', () => ({
  useConfig: () => ({ data: { tracking: state.tracking } }),
}));
vi.mock('../api/hooks/useUserSettings', () => ({
  useUserSettingValue: () => state.optOut,
}));
vi.mock('./ConsentBar', () => ({ ConsentBar: () => <div>consent?</div> }));

const DOC = '8f3c1d2e-4a5b-6c7d-8e9f-0a1b2c3d4e5f';

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      {
        element: <TrackingBoot />,
        children: [{ path: '/reviews/:documentId', element: <div>review</div> }],
      },
    ],
    { initialEntries: [path] },
  );
  return render(<RouterProvider router={router} />);
}

describe('TrackingBoot (issue #910)', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    state.optOut = 'false';
    state.tracking = {
      provider: 'umami',
      siteId: 'site-1',
      consentRequired: false,
      respectDnt: false,
      trackPrivilegedRoles: false,
    };
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('measures without loading any script, and never sends the real path', async () => {
    const appended = vi.spyOn(document.head, 'appendChild');
    const { unmount } = renderAt(`/reviews/${DOC}`);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/t/c/api/send');
    expect(String(init.body)).toContain('/reviews/:documentId');
    expect(String(init.body)).not.toContain(DOC);
    // No vendor code is ever executed in the application's origin.
    expect(document.querySelector('script')).toBeNull();
    expect(appended.mock.calls.some(([node]) => node instanceof HTMLScriptElement)).toBe(false);

    trackEvent('review_created');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String((fetchMock.mock.calls[1][1] as RequestInit).body)).toContain('review_created');

    unmount();
    // Once unmounted, nothing is measured any more.
    trackEvent('review_created');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    appended.mockRestore();
  });

  it('sends nothing while consent is still being asked for', async () => {
    state.tracking = { ...state.tracking!, consentRequired: true };
    const { findByText } = renderAt(`/reviews/${DOC}`);

    await findByText('consent?');
    trackEvent('annotation_created');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
