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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { auditApi, axiosInstance } from './config';

/**
 * Exercises the REAL generated audit client (not a mock) to prove the filter
 * parameters actually reach the request URL — the layer the hook/page tests
 * cannot see because they stub `auditApi`. Spies on the shared axios instance
 * and reads the URL the client built.
 */
function capturedUrl(): string {
  const spy = vi.mocked(axiosInstance.request);
  const arg = spy.mock.calls[0][0] as { url?: string };
  return arg.url ?? '';
}

describe('auditApi.listAuditEvents request serialization', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sends actorSystem=true when filtering to system events', async () => {
    vi.spyOn(axiosInstance, 'request').mockResolvedValue({ data: { items: [], total: 0 } });

    await auditApi.listAuditEvents({ actorSystem: true, page: 0, size: 20 });

    const url = capturedUrl();
    expect(url).toContain('actorSystem=true');
    expect(url).not.toContain('actorId=');
  });

  it('sends actorId when filtering to a specific actor', async () => {
    vi.spyOn(axiosInstance, 'request').mockResolvedValue({ data: { items: [], total: 0 } });

    await auditApi.listAuditEvents({ actorId: 'user-1', page: 0, size: 20 });

    const url = capturedUrl();
    expect(url).toContain('actorId=user-1');
    expect(url).not.toContain('actorSystem=');
  });

  it('omits both actor filters when neither is set', async () => {
    vi.spyOn(axiosInstance, 'request').mockResolvedValue({ data: { items: [], total: 0 } });

    await auditApi.listAuditEvents({ page: 0, size: 20 });

    const url = capturedUrl();
    expect(url).not.toContain('actorSystem=');
    expect(url).not.toContain('actorId=');
  });
});
