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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import { usePdfDocument } from './usePdfDocument';

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: {},
  getDocument: vi.fn(),
}));

const { getDocument } = await import('pdfjs-dist');
const getDocumentMock = vi.mocked(getDocument);

/** A resolved/rejected loading task with a spyable destroy(). */
function loadingTask(promise: Promise<unknown>) {
  const destroy = vi.fn().mockResolvedValue(undefined);
  return { task: { promise, destroy } as never, destroy };
}

const fakePdf = { numPages: 3 } as PDFDocumentProxy;

beforeEach(() => {
  vi.clearAllMocks();
});

describe('usePdfDocument', () => {
  it('stays idle and never touches pdf.js without data', () => {
    const { result } = renderHook(() => usePdfDocument(undefined));

    expect(result.current).toEqual({ pdf: null, error: null });
    expect(getDocumentMock).not.toHaveBeenCalled();
  });

  it('parses the bytes and exposes the document, handing pdf.js a copy', async () => {
    const { task } = loadingTask(Promise.resolve(fakePdf));
    getDocumentMock.mockReturnValue(task);
    const data = new Uint8Array([1, 2, 3, 4]).buffer;

    const { result } = renderHook(() => usePdfDocument(data));

    await waitFor(() => expect(result.current.pdf).toBe(fakePdf));
    expect(result.current.error).toBeNull();

    const arg = getDocumentMock.mock.calls[0][0] as { data: ArrayBuffer };
    expect(arg.data).not.toBe(data); // a defensive slice, not the cached buffer
    expect(arg.data.byteLength).toBe(4);
  });

  it('surfaces the parser error message on a rejected load', async () => {
    const { task } = loadingTask(Promise.reject(new Error('bad header')));
    getDocumentMock.mockReturnValue(task);
    const data = new Uint8Array([1]).buffer;

    const { result } = renderHook(() => usePdfDocument(data));

    await waitFor(() => expect(result.current.error).toBe('bad header'));
    expect(result.current.pdf).toBeNull();
  });

  it('falls back to a generic message when the rejection is not an Error', async () => {
    const { task } = loadingTask(Promise.reject('nope'));
    getDocumentMock.mockReturnValue(task);
    const data = new Uint8Array([1]).buffer;

    const { result } = renderHook(() => usePdfDocument(data));

    await waitFor(() => expect(result.current.error).toBe('Failed to parse PDF'));
  });

  it('destroys the loading task on unmount so the worker handle is released', async () => {
    const { task, destroy } = loadingTask(Promise.resolve(fakePdf));
    getDocumentMock.mockReturnValue(task);
    const data = new Uint8Array([1]).buffer;

    const { result, unmount } = renderHook(() => usePdfDocument(data));
    await waitFor(() => expect(result.current.pdf).toBe(fakePdf));

    unmount();

    expect(destroy).toHaveBeenCalledOnce();
  });

  it('tears down the previous task when the data changes', async () => {
    const first = loadingTask(Promise.resolve(fakePdf));
    const second = loadingTask(Promise.resolve(fakePdf));
    getDocumentMock.mockReturnValueOnce(first.task).mockReturnValueOnce(second.task);

    const { result, rerender } = renderHook(({ data }) => usePdfDocument(data), {
      initialProps: { data: new Uint8Array([1]).buffer },
    });
    await waitFor(() => expect(result.current.pdf).toBe(fakePdf));

    rerender({ data: new Uint8Array([2, 2]).buffer });

    expect(first.destroy).toHaveBeenCalledOnce();
    expect(getDocumentMock).toHaveBeenCalledTimes(2);
  });
});
