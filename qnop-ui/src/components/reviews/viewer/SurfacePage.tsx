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
import Paper from '@mui/material/Paper';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import type { AnnotationView, NormalizedBox, RenderedSurface } from '../../../api/generated';
import type { TextSelectionOffsets } from './anchoring';
import { HighlightLayer } from './HighlightLayer';
import { RegionSelectLayer } from './RegionSelectLayer';
import { TextSpanLayer } from './TextSpanLayer';
import type { ViewerTool } from './ViewerToolbar';

interface SurfacePageProps {
  pdf: PDFDocumentProxy;
  /** Zero-based page index (== surface index, one surface per PDF page). */
  pageIndex: number;
  /**
   * The server-extracted surface. Absent while extraction is still running —
   * the page then renders pixels only, without annotation layers (ADR-0033:
   * the version is visible immediately).
   */
  surface?: RenderedSurface;
  /** Display width in CSS pixels. */
  width: number;
  annotations: AnnotationView[];
  activeAnnotationId: string | null;
  onSelectAnnotation: (annotationId: string) => void;
  tool: ViewerTool;
  /** False disables both selection layers (extraction pending, or read-only). */
  canAnnotate: boolean;
  pendingAnchorBox: NormalizedBox | null;
  onTextSelected: (selection: TextSelectionOffsets) => void;
  onRegionSelected: (surfaceIndex: number, box: NormalizedBox) => void;
}

/**
 * One document page: the pdf.js pixel canvas plus the overlay stack —
 * highlights from stored boxes, the selectable server text layer, and the
 * region rubber-band. The page box uses the server surface's aspect ratio
 * (falling back to the pdf.js viewport before extraction), and every overlay
 * is positioned in percent, so all layers stay aligned at any zoom.
 */
export function SurfacePage({
  pdf,
  pageIndex,
  surface,
  width,
  annotations,
  activeAnnotationId,
  onSelectAnnotation,
  tool,
  canAnnotate,
  pendingAnchorBox,
  onTextSelected,
  onRegionSelected,
}: SurfacePageProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(false);
  const [fallbackAspect, setFallbackAspect] = useState<number | null>(null);

  // Render lazily: pages far outside the viewport keep an empty canvas.
  useEffect(() => {
    const element = containerRef.current;
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
      if (!surface) setFallbackAspect(base.width / base.height);
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
  }, [pdf, pageIndex, surface, width, visible]);

  const aspect = surface ? surface.width / surface.height : (fallbackAspect ?? Math.SQRT1_2);
  const pageHeight = width / aspect;

  return (
    <Paper
      ref={containerRef}
      variant="outlined"
      square
      data-testid={`surface-page-${pageIndex}`}
      sx={{
        position: 'relative',
        width,
        aspectRatio: `${aspect}`,
        bgcolor: '#fff',
        overflow: 'hidden',
      }}
    >
      <canvas
        ref={canvasRef}
        aria-label={`Page ${pageIndex + 1}`}
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
      />
      {surface && (
        <>
          <TextSpanLayer
            spans={surface.textSpans}
            surfaceIndex={surface.index}
            pageHeight={pageHeight}
            enabled={canAnnotate && tool === 'text' && surface.textSpans.length > 0}
            onTextSelected={onTextSelected}
          />
          <HighlightLayer
            annotations={annotations}
            surfaceIndex={surface.index}
            activeAnnotationId={activeAnnotationId}
            onSelect={onSelectAnnotation}
            pendingBox={pendingAnchorBox}
          />
          <RegionSelectLayer
            surfaceIndex={surface.index}
            enabled={canAnnotate && tool === 'region'}
            onRegionSelected={onRegionSelected}
          />
        </>
      )}
    </Paper>
  );
}
