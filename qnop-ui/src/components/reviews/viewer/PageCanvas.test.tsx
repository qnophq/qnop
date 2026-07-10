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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import { PageCanvas } from './PageCanvas';

// A controllable IntersectionObserver: the setup.ts stub never fires, but the
// canvas only renders once its observer reports the page as visible.
let intersect: (() => void) | null = null;
const realIO = window.IntersectionObserver;

beforeEach(() => {
  window.IntersectionObserver = class {
    constructor(private cb: IntersectionObserverCallback) {}
    observe = () => {
      intersect = () =>
        this.cb([{ isIntersecting: true } as IntersectionObserverEntry], this as never);
    };
    unobserve = vi.fn();
    disconnect = vi.fn();
    takeRecords = () => [];
  } as unknown as typeof IntersectionObserver;
});

afterEach(() => {
  window.IntersectionObserver = realIO;
  intersect = null;
  vi.clearAllMocks();
});

/** A pdf.js page 100×200 at scale 1 (aspect 0.5), with a spyable render task. */
function fakePdf() {
  // A fresh cancel spy per document: testing-library's auto-cleanup unmounts the
  // previous test's canvas (and cancels its render) after our clearAllMocks runs,
  // so a shared spy would carry that stray call into the next test.
  const cancel = vi.fn();
  const page = {
    getViewport: vi.fn(({ scale }: { scale: number }) => ({
      width: 100 * scale,
      height: 200 * scale,
    })),
    render: vi.fn(() => ({ promise: Promise.resolve(), cancel })),
  };
  const pdf = { getPage: vi.fn().mockResolvedValue(page) } as unknown as PDFDocumentProxy;
  return { pdf, page, cancel };
}

describe('PageCanvas', () => {
  it('renders nothing until the page scrolls into view', () => {
    const { pdf } = fakePdf();
    render(<PageCanvas pdf={pdf} pageIndex={0} width={300} ariaLabel="Page 1" />);

    expect(pdf.getPage).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Page 1')).toBeInTheDocument();
  });

  it('renders the pdf.js page and sizes the canvas by width × devicePixelRatio', async () => {
    const { pdf, page } = fakePdf();
    const onAspect = vi.fn();
    render(
      <PageCanvas pdf={pdf} pageIndex={0} width={300} ariaLabel="Page 1" onAspect={onAspect} />,
    );

    act(() => intersect?.());

    await waitFor(() => expect(pdf.getPage).toHaveBeenCalledWith(1));
    await waitFor(() => expect(page.render).toHaveBeenCalledOnce());

    // Aspect from the base (scale-1) viewport: 100 / 200.
    expect(onAspect).toHaveBeenCalledWith(0.5);
    // Render viewport at scale (300/100)*dpr — dpr is 1 under jsdom.
    const canvas = screen.getByLabelText('Page 1') as HTMLCanvasElement;
    expect(canvas.width).toBe(300);
    expect(canvas.height).toBe(600);
  });

  it('cancels the in-flight render when it unmounts', async () => {
    const { pdf, page, cancel } = fakePdf();
    const { unmount } = render(
      <PageCanvas pdf={pdf} pageIndex={2} width={300} ariaLabel="Page 3" />,
    );

    act(() => intersect?.());
    await waitFor(() => expect(page.render).toHaveBeenCalledOnce());

    unmount();

    expect(cancel).toHaveBeenCalledOnce();
  });
});
