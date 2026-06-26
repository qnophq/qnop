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

import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMailTemplatePreview, type MailTemplatePreviewInput } from './useMailTemplatePreview';

const { previewMock } = vi.hoisted(() => ({ previewMock: vi.fn() }));

vi.mock('../config', () => ({
  adminEmailApi: { previewMailTemplate: previewMock },
}));

const BASE: MailTemplatePreviewInput = {
  key: 'auth.password_reset',
  subject: 'Hi {{siteName}}',
  bodyPlain: 'Body {{recipientName}}',
};

const rendered = (subject: string) => ({
  data: { subject, bodyPlain: 'b', sampleVars: {} },
});

/** Advances fake timers and flushes the resulting promise microtasks inside act(). */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  previewMock.mockReset();
  previewMock.mockResolvedValue(rendered('Hi qnop'));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useMailTemplatePreview', () => {
  it('stays idle and never calls the API while the body is empty (enabled-gate)', async () => {
    const { result } = renderHook(() => useMailTemplatePreview({ ...BASE, bodyPlain: '   ' }));

    expect(result.current.status).toBe('idle');
    await advance(600);

    expect(previewMock).not.toHaveBeenCalled();
    expect(result.current.status).toBe('idle');
  });

  it('debounces rapid edits into a single render and reports live status', async () => {
    const { result, rerender } = renderHook((props) => useMailTemplatePreview(props), {
      initialProps: BASE,
    });

    expect(result.current.status).toBe('stale');
    rerender({ ...BASE, subject: 'A' });
    rerender({ ...BASE, subject: 'AB' });

    await advance(500);

    expect(previewMock).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('live');
    expect(result.current.data?.subject).toBe('Hi qnop');
  });

  it('discards a slow earlier response that arrives out of order', async () => {
    let resolveFirst!: (value: unknown) => void;
    previewMock
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveFirst = resolve;
        }),
      )
      .mockResolvedValueOnce(rendered('SECOND'));

    const { result, rerender } = renderHook((props) => useMailTemplatePreview(props), {
      initialProps: BASE,
    });

    await advance(500); // fires the first request — still in flight
    expect(result.current.status).toBe('syncing');

    rerender({ ...BASE, subject: 'changed' });
    await advance(500); // fires the second request — resolves to SECOND

    expect(result.current.data?.subject).toBe('SECOND');

    // The slow first response lands late and must be ignored.
    await act(async () => {
      resolveFirst(rendered('FIRST'));
    });
    expect(result.current.data?.subject).toBe('SECOND');
  });

  it('reports an error status when the render fails', async () => {
    previewMock.mockRejectedValue({ response: { data: { message: 'Boom' } } });

    const { result } = renderHook(() => useMailTemplatePreview(BASE));
    await advance(500);

    expect(result.current.status).toBe('error');
    expect(result.current.error).toBeTruthy();
  });

  it('refresh() renders immediately, bypassing the debounce', async () => {
    const { result } = renderHook(() => useMailTemplatePreview(BASE));

    await act(async () => {
      result.current.refresh();
    });

    expect(previewMock).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('live');
  });
});
