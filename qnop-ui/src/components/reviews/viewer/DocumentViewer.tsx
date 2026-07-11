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

import { useEffect, useImperativeHandle, useRef, useState } from 'react';
import type { Ref } from 'react';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import type {
  Anchor,
  AnnotationView,
  NormalizedBox,
  RenderedSurface,
} from '../../../api/generated';
import type { ScreenPosition, TextSelectionOffsets } from './anchoring';
import { AnnotationHoverCard } from './AnnotationHoverCard';
import { SurfacePage } from './SurfacePage';
import type { ViewerTool } from './ViewerToolbar';

/** Keep hairline page borders visible inside the scroll area. */
const PAGE_GUTTER_PX = 2;

/** Imperative surface for the toolbar's page navigation. */
export interface DocumentViewerHandle {
  scrollToPage: (pageIndex: number) => void;
}

interface DocumentViewerProps {
  ref?: Ref<DocumentViewerHandle>;
  pdf: PDFDocumentProxy;
  /** Server surfaces; undefined while extraction is running (pixels only then). */
  surfaces?: RenderedSurface[];
  zoom: number;
  annotations: AnnotationView[];
  activeAnnotationId: string | null;
  hoverAnnotationId?: string | null;
  onSelectAnnotation: (annotationId: string) => void;
  onHoverAnnotation?: (annotationId: string | null) => void;
  /** Reports the page currently crossing the upper third of the viewport. */
  onVisiblePageChange?: (pageIndex: number) => void;
  tool: ViewerTool;
  canAnnotate: boolean;
  pendingAnchor: Anchor | null;
  onTextSelected: (selection: TextSelectionOffsets, at: ScreenPosition) => void;
  onRegionSelected: (surfaceIndex: number, box: NormalizedBox, at: ScreenPosition) => void;
  /**
   * Focus-mode overlay state (issue #291): non-null dims every page;
   * `spotlight` names the page and rect that stay sharp.
   */
  focusScrim?: {
    spotlight: { surfaceIndex: number; box: NormalizedBox } | null;
    onDismiss: () => void;
  } | null;
}

/**
 * The scrollable page stack — its own scroll pane inside the review
 * workspace. Fit-width at zoom 1: the page width tracks the container via
 * ResizeObserver; larger zooms overflow horizontally inside the viewer, never
 * the app shell. A scroll spy reports the current page to the toolbar, and a
 * hovered annotation (from the panel) is scrolled into view when off-screen —
 * the prototype's card↔mark linking.
 */
export function DocumentViewer({
  ref,
  pdf,
  surfaces,
  zoom,
  annotations,
  activeAnnotationId,
  hoverAnnotationId,
  onSelectAnnotation,
  onHoverAnnotation,
  onVisiblePageChange,
  tool,
  canAnnotate,
  pendingAnchor,
  onTextSelected,
  onRegionSelected,
  focusScrim,
}: DocumentViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const pageRefs = useRef<(HTMLDivElement | null)[]>([]);
  const [containerWidth, setContainerWidth] = useState(0);
  // Preview card for marks only: hovering a PANEL card links the mark but must
  // not stack a second card over the document. The pointer position is kept in
  // a ref (no re-renders) and read once when the card shows.
  const [previewId, setPreviewId] = useState<string | null>(null);
  const pointerRef = useRef<ScreenPosition | null>(null);

  const handleMarkHover = (annotationId: string | null) => {
    setPreviewId(annotationId);
    onHoverAnnotation?.(annotationId);
  };

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return undefined;
    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) setContainerWidth(width);
    });
    observer.observe(element);
    setContainerWidth(element.clientWidth);
    return () => observer.disconnect();
  }, []);

  useImperativeHandle(ref, () => ({
    scrollToPage: (pageIndex: number) => {
      pageRefs.current[pageIndex]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    },
  }));

  // Scroll spy: report the page whose top has crossed the upper third of the
  // viewport. rAF-throttled — scroll fires far more often than the answer
  // changes.
  useEffect(() => {
    const container = containerRef.current;
    if (!container || !onVisiblePageChange) return undefined;
    let frame = 0;
    const report = () => {
      frame = 0;
      const probe = container.getBoundingClientRect().top + container.clientHeight / 3;
      let current = 0;
      pageRefs.current.forEach((page, index) => {
        if (page && page.getBoundingClientRect().top <= probe) current = index;
      });
      onVisiblePageChange(current);
    };
    const handleScroll = () => {
      if (!frame) frame = requestAnimationFrame(report);
    };
    container.addEventListener('scroll', handleScroll, { passive: true });
    report();
    return () => {
      container.removeEventListener('scroll', handleScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, [onVisiblePageChange]);

  // A panel click, the focus card's prev/next walk (#291) or a deep link (the
  // tasks view's "Show in document", ?annotation= permalinks — issue #403)
  // scrolls the possibly off-screen highlight into view. On a fresh navigation
  // the marks are NOT there yet — data arrives after the first render, and the
  // page stack itself waits for the measured containerWidth — so the effect
  // retries on every data/layout pass until the mark exists, but scrolls only
  // once per id (the ref), so later refetches never yank the reader back.
  const scrolledToAnnotationRef = useRef<string | null>(null);
  useEffect(() => {
    if (!activeAnnotationId) {
      scrolledToAnnotationRef.current = null; // re-selecting scrolls again
      return;
    }
    if (scrolledToAnnotationRef.current === activeAnnotationId) return;
    const mark = document.getElementById(`annotation-highlight-${activeAnnotationId}`);
    if (!mark) return; // marks not rendered yet — retry once the data lands
    scrolledToAnnotationRef.current = activeAnnotationId;
    // Instant by design: "show in document" means a jump, and Chromium can
    // wedge smooth container scrolls started while the page stack is still
    // settling — the animation then silently never moves. Instant is
    // deterministic and needs no reduced-motion branch.
    mark.scrollIntoView({ behavior: 'auto', block: 'center' });
  }, [activeAnnotationId, annotations, surfaces, containerWidth]);

  const pageWidth = Math.max(0, (containerWidth - 2 * PAGE_GUTTER_PX) * zoom);
  const pageCount = surfaces?.length ?? pdf.numPages;
  // Only a PANEL-card hover stages the mark's spotlight treatment; hovering
  // the mark directly keeps the gentle CSS :hover (earlier feedback: no ring).
  const linkHoverId = hoverAnnotationId === previewId ? null : (hoverAnnotationId ?? null);

  return (
    <Box
      ref={containerRef}
      component="section"
      aria-label="Document pages"
      onMouseMove={(event) => {
        pointerRef.current = { left: event.clientX, top: event.clientY };
      }}
      sx={{
        overflow: 'auto',
        height: '100%',
        p: `${PAGE_GUTTER_PX}px`,
        '@media (prefers-reduced-motion: reduce)': { scrollBehavior: 'auto' },
      }}
    >
      {pageWidth > 0 && (
        <Stack spacing={2.5} sx={{ width: 'max-content', minWidth: '100%', alignItems: 'center' }}>
          {Array.from({ length: pageCount }, (_, pageIndex) => {
            const surface = surfaces?.find((s) => s.index === pageIndex);
            return (
              <div
                key={pageIndex}
                ref={(el) => {
                  pageRefs.current[pageIndex] = el;
                }}
              >
                <SurfacePage
                  pdf={pdf}
                  pageIndex={pageIndex}
                  surface={surface}
                  width={pageWidth}
                  annotations={annotations}
                  activeAnnotationId={activeAnnotationId}
                  hoverAnnotationId={linkHoverId}
                  onSelectAnnotation={(id) => {
                    setPreviewId(null);
                    onSelectAnnotation(id);
                  }}
                  onHoverAnnotation={handleMarkHover}
                  tool={tool}
                  canAnnotate={canAnnotate}
                  pendingAnchor={pendingAnchor}
                  onTextSelected={onTextSelected}
                  onRegionSelected={onRegionSelected}
                  focusScrim={
                    focusScrim && {
                      box:
                        focusScrim.spotlight?.surfaceIndex === pageIndex
                          ? focusScrim.spotlight.box
                          : null,
                      onDismiss: focusScrim.onDismiss,
                    }
                  }
                />
              </div>
            );
          })}
        </Stack>
      )}
      {(() => {
        const preview = previewId ? annotations.find((a) => a.id === previewId) : undefined;
        return preview ? (
          <AnnotationHoverCard annotation={preview} getAnchorPosition={() => pointerRef.current} />
        ) : null;
      })()}
    </Box>
  );
}
