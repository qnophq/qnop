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
import { SurfacePage } from './SurfacePage';
import type { ViewerTool } from './ViewerToolbar';

/** Keep hairline page borders visible inside the scroll area. */
const PAGE_GUTTER_PX = 2;

interface DocumentViewerProps {
  pdf: PDFDocumentProxy;
  /** Server surfaces; undefined while extraction is running (pixels only then). */
  surfaces?: RenderedSurface[];
  zoom: number;
  annotations: AnnotationView[];
  activeAnnotationId: string | null;
  onSelectAnnotation: (annotationId: string) => void;
  tool: ViewerTool;
  canAnnotate: boolean;
  pendingAnchor: Anchor | null;
  onTextSelected: (selection: TextSelectionOffsets, at: ScreenPosition) => void;
  onRegionSelected: (surfaceIndex: number, box: NormalizedBox, at: ScreenPosition) => void;
}

/**
 * The scrollable page stack. Fit-width at zoom 1: the page width tracks the
 * container via ResizeObserver; larger zooms overflow horizontally inside the
 * viewer, never the app shell.
 */
export function DocumentViewer({
  pdf,
  surfaces,
  zoom,
  annotations,
  activeAnnotationId,
  onSelectAnnotation,
  tool,
  canAnnotate,
  pendingAnchor,
  onTextSelected,
  onRegionSelected,
}: DocumentViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState(0);

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

  // A panel click scrolls the (possibly off-screen) highlight into view.
  useEffect(() => {
    if (!activeAnnotationId) return;
    document
      .getElementById(`annotation-highlight-${activeAnnotationId}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [activeAnnotationId]);

  const pageWidth = Math.max(0, (containerWidth - 2 * PAGE_GUTTER_PX) * zoom);
  const pageCount = surfaces?.length ?? pdf.numPages;

  return (
    <Box ref={containerRef} sx={{ overflowX: 'auto', p: `${PAGE_GUTTER_PX}px` }}>
      {pageWidth > 0 && (
        <Stack spacing={2} sx={{ width: 'max-content', minWidth: '100%', alignItems: 'center' }}>
          {Array.from({ length: pageCount }, (_, pageIndex) => {
            const surface = surfaces?.find((s) => s.index === pageIndex);
            return (
              <SurfacePage
                key={pageIndex}
                pdf={pdf}
                pageIndex={pageIndex}
                surface={surface}
                width={pageWidth}
                annotations={annotations}
                activeAnnotationId={activeAnnotationId}
                onSelectAnnotation={onSelectAnnotation}
                tool={tool}
                canAnnotate={canAnnotate}
                pendingAnchor={pendingAnchor}
                onTextSelected={onTextSelected}
                onRegionSelected={onRegionSelected}
              />
            );
          })}
        </Stack>
      )}
    </Box>
  );
}
