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

import { useEffect, useRef, useState } from 'react';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';

interface PageCanvasProps {
  pdf: PDFDocumentProxy;
  /** Zero-based page index. */
  pageIndex: number;
  /** Display width in CSS pixels — the backing store scales by devicePixelRatio. */
  width: number;
  ariaLabel: string;
  /**
   * Reports the pdf.js aspect ratio (width/height) once the page header is
   * read — callers without a server surface size their page box from it.
   */
  onAspect?: (aspect: number) => void;
}

/**
 * One pdf.js pixel canvas, shared by the review viewer and the version-diff
 * panes (pixels only — all annotation/diff geometry comes from the server,
 * ADR-0032). Renders lazily: pages far outside the viewport keep an empty
 * canvas. Re-renders cancel the previous pass; the canvas fills its positioned
 * parent, so overlay layers in percent stay aligned at any zoom.
 */
export function PageCanvas({ pdf, pageIndex, width, ariaLabel, onAspect }: PageCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(false);
  // The latest callback without retriggering the render effect on identity churn.
  const onAspectRef = useRef(onAspect);
  useEffect(() => {
    onAspectRef.current = onAspect;
  }, [onAspect]);

  useEffect(() => {
    const element = canvasRef.current;
    if (!element) return undefined;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) setVisible(true);
      },
      { rootMargin: '100% 0px' },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!visible) return undefined;
    let cancelled = false;
    let renderTask: RenderTask | undefined;
    void pdf.getPage(pageIndex + 1).then((page) => {
      if (cancelled) return;
      const base = page.getViewport({ scale: 1 });
      onAspectRef.current?.(base.width / base.height);
      const dpr = window.devicePixelRatio || 1;
      const viewport = page.getViewport({ scale: (width / base.width) * dpr });
      const canvas = canvasRef.current;
      if (!canvas) return;
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      renderTask = page.render({ canvas, viewport });
      // Re-renders cancel the previous pass; swallow the cancellation rejection.
      renderTask.promise.catch(() => {});
    });
    return () => {
      cancelled = true;
      renderTask?.cancel();
    };
  }, [pdf, pageIndex, width, visible]);

  return (
    <canvas
      ref={canvasRef}
      aria-label={ariaLabel}
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
    />
  );
}
