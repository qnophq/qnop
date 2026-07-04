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

import { useState } from 'react';
import Paper from '@mui/material/Paper';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import type {
  Anchor,
  AnnotationView,
  NormalizedBox,
  RenderedSurface,
} from '../../../api/generated';
import type { ScreenPosition, TextSelectionOffsets } from './anchoring';
import { FocusScrimLayer } from '../focus/FocusScrimLayer';
import { HighlightLayer } from './HighlightLayer';
import { PageCanvas } from './PageCanvas';
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
  hoverAnnotationId?: string | null;
  onSelectAnnotation: (annotationId: string) => void;
  onHoverAnnotation?: (annotationId: string | null) => void;
  tool: ViewerTool;
  /** False disables both selection layers (extraction pending, or read-only). */
  canAnnotate: boolean;
  pendingAnchor: Anchor | null;
  onTextSelected: (selection: TextSelectionOffsets, at: ScreenPosition) => void;
  onRegionSelected: (surfaceIndex: number, box: NormalizedBox, at: ScreenPosition) => void;
  /**
   * Focus-mode scrim (issue #291): non-null dims this page — with a sharp
   * spotlight hole when `box` is set (the spotlit passage lives here). The
   * scrim sits UNDER the highlight layer, so marks stay crisp and clickable.
   */
  focusScrim?: { box: NormalizedBox | null; onDismiss: () => void } | null;
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
  hoverAnnotationId,
  onSelectAnnotation,
  onHoverAnnotation,
  tool,
  canAnnotate,
  pendingAnchor,
  onTextSelected,
  onRegionSelected,
  focusScrim,
}: SurfacePageProps) {
  const [fallbackAspect, setFallbackAspect] = useState<number | null>(null);

  const aspect = surface ? surface.width / surface.height : (fallbackAspect ?? Math.SQRT1_2);

  return (
    <Paper
      elevation={0}
      square
      data-testid={`surface-page-${pageIndex}`}
      sx={(theme) => ({
        position: 'relative',
        width,
        aspectRatio: `${aspect}`,
        bgcolor: '#fff',
        overflow: 'hidden',
        borderRadius: '3px',
        // "Document on a desk": a soft ambient shadow in light mode; dark mode
        // keeps a hairline edge instead (shadows vanish on dark surfaces).
        boxShadow: theme.palette.mode === 'light' ? '0 4px 24px rgba(1, 32, 66, 0.10)' : 'none',
        border: theme.palette.mode === 'dark' ? `1px solid ${theme.palette.divider}` : 'none',
      })}
    >
      <PageCanvas
        pdf={pdf}
        pageIndex={pageIndex}
        width={width}
        ariaLabel={`Page ${pageIndex + 1}`}
        onAspect={surface ? undefined : setFallbackAspect}
      />
      {surface && (
        <>
          <TextSpanLayer
            spans={surface.textSpans}
            surfaceIndex={surface.index}
            enabled={canAnnotate && tool === 'text' && surface.textSpans.length > 0}
            onTextSelected={onTextSelected}
          />
          {/* The rubber-band sits BELOW the highlights: existing marks stay
              hoverable/clickable in region mode exactly as in text mode, and
              pointer capture keeps an already-started drag alive across them
              — only starting a drag on a mark belongs to the mark. */}
          <RegionSelectLayer
            surfaceIndex={surface.index}
            enabled={canAnnotate && tool === 'region'}
            onRegionSelected={onRegionSelected}
          />
          {focusScrim && (
            <FocusScrimLayer
              spotlight={focusScrim.box}
              onDismiss={focusScrim.onDismiss}
              surfaceIndex={surface.index}
            />
          )}
          <HighlightLayer
            annotations={annotations}
            surfaceIndex={surface.index}
            spans={surface.textSpans}
            activeAnnotationId={activeAnnotationId}
            hoverAnnotationId={hoverAnnotationId}
            onSelect={onSelectAnnotation}
            onHover={onHoverAnnotation}
            pendingAnchor={pendingAnchor}
          />
        </>
      )}
    </Paper>
  );
}
