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
import type { Ref } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { History } from 'lucide-react';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import type { DiffChange, RenderedSurface } from '../../../api/generated';
import { tokens } from '../../../theme/tokens';
import { PageCanvas } from '../viewer/PageCanvas';
import { DiffHighlightLayer } from './DiffHighlightLayer';
import type { DiffSide } from './diffModel';

const PAGE_GUTTER_PX = 24;

interface ComparePaneProps {
  side: DiffSide;
  versionNumber: number;
  /** Header meta, e.g. "12 Jun 2026 · Maxim Kalina". */
  metaText: string;
  /** Extra header text on the right, e.g. "3 changes" (the newer pane). */
  trailingText?: string;
  /** Parsed pdf.js document; null while the original is still loading/parsing. */
  pdf: PDFDocumentProxy | null;
  pdfError: string | null;
  /** Server surfaces of this version — page aspect + line pitch for the bands. */
  surfaces?: RenderedSurface[];
  changes: DiffChange[];
  activeChangeIndex: number | null;
  onSelectChange: (changeIndex: number) => void;
  /** The scroll container ref (callback ref) — the page couples both panes (sync scroll). */
  scrollRef: Ref<HTMLDivElement>;
}

/**
 * One side of the version comparison: a sticky version header over the
 * scrollable page stack of that version's rendered original, with the located
 * changes painted by {@link DiffHighlightLayer}. The baseline pane reads
 * neutral, the newer pane carries the brand-blue accent — the prototype's
 * from/to colour cue. Fit-width like the review viewer: the page width tracks
 * the pane via ResizeObserver.
 */
export function ComparePane({
  side,
  versionNumber,
  metaText,
  trailingText,
  pdf,
  pdfError,
  surfaces,
  changes,
  activeChangeIndex,
  onSelectChange,
  scrollRef,
}: ComparePaneProps) {
  const theme = useTheme();
  const stackRef = useRef<HTMLDivElement>(null);
  const [stackWidth, setStackWidth] = useState(0);
  const [fallbackAspects, setFallbackAspects] = useState<Record<number, number>>({});

  useEffect(() => {
    const element = stackRef.current;
    if (!element) return undefined;
    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) setStackWidth(width);
    });
    observer.observe(element);
    setStackWidth(element.clientWidth);
    return () => observer.disconnect();
  }, []);

  const pageWidth = Math.max(0, stackWidth - 2 * PAGE_GUTTER_PX);
  const pageCount = surfaces?.length ?? pdf?.numPages ?? 0;
  const isTo = side === 'to';
  const badge = isTo ? theme.qnop.badge.blue : null;

  return (
    <Box
      data-testid={`compare-pane-${side}`}
      sx={{ display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0, flex: 1 }}
    >
      <Stack
        direction="row"
        spacing={1}
        sx={{
          alignItems: 'center',
          px: 2,
          py: 1,
          bgcolor: 'background.paper',
          borderBottom: `1px solid ${theme.palette.divider}`,
          flexShrink: 0,
        }}
      >
        <Stack
          direction="row"
          spacing={0.5}
          component="span"
          sx={{
            alignItems: 'center',
            px: 1,
            py: 0.25,
            borderRadius: 99,
            fontSize: 12,
            fontWeight: 600,
            fontFamily: tokens.font.mono,
            ...(badge
              ? {
                  bgcolor: badge.bg,
                  color: theme.palette.mode === 'dark' ? badge.fgDark : badge.fg,
                  border: `1px solid ${badge.border}`,
                }
              : {
                  color: 'text.secondary',
                  border: `1px solid ${theme.palette.divider}`,
                }),
          }}
        >
          <History size={11} aria-hidden />
          <span>{`v${versionNumber}`}</span>
        </Stack>
        <Typography variant="caption" color="text.secondary" noWrap>
          {metaText}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {trailingText && (
          <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
            {trailingText}
          </Typography>
        )}
      </Stack>

      <Box
        ref={scrollRef}
        data-testid={`compare-scroll-${side}`}
        sx={{ flex: 1, minHeight: 0, overflow: 'auto', bgcolor: theme.qnop.surface2 }}
      >
        <Stack ref={stackRef} spacing={2} sx={{ p: `${PAGE_GUTTER_PX}px`, alignItems: 'center' }}>
          {pdfError && (
            <Typography variant="body2" color="error" sx={{ py: 4 }}>
              The document could not be rendered. {pdfError}
            </Typography>
          )}
          {!pdf && !pdfError && <CircularProgress size={24} sx={{ my: 6 }} />}
          {pdf &&
            pageWidth > 0 &&
            Array.from({ length: pageCount }, (_, pageIndex) => {
              const surface = surfaces?.[pageIndex];
              const aspect = surface
                ? surface.width / surface.height
                : (fallbackAspects[pageIndex] ?? Math.SQRT1_2);
              return (
                <Paper
                  key={pageIndex}
                  elevation={0}
                  square
                  data-testid={`compare-page-${side}-${pageIndex}`}
                  sx={{
                    position: 'relative',
                    width: pageWidth,
                    aspectRatio: `${aspect}`,
                    bgcolor: '#fff',
                    overflow: 'hidden',
                    borderRadius: '3px',
                    flexShrink: 0,
                    boxShadow:
                      theme.palette.mode === 'light' ? '0 4px 24px rgba(1, 32, 66, 0.10)' : 'none',
                    border:
                      theme.palette.mode === 'dark' ? `1px solid ${theme.palette.divider}` : 'none',
                  }}
                >
                  <PageCanvas
                    pdf={pdf}
                    pageIndex={pageIndex}
                    width={pageWidth}
                    ariaLabel={`Version ${versionNumber}, page ${pageIndex + 1}`}
                    onAspect={
                      surface
                        ? undefined
                        : (value) =>
                            setFallbackAspects((current) =>
                              current[pageIndex] === value
                                ? current
                                : { ...current, [pageIndex]: value },
                            )
                    }
                  />
                  <DiffHighlightLayer
                    changes={changes}
                    side={side}
                    surfaceIndex={pageIndex}
                    spans={surface?.textSpans}
                    activeChangeIndex={activeChangeIndex}
                    onSelectChange={onSelectChange}
                  />
                </Paper>
              );
            })}
        </Stack>
      </Box>
    </Box>
  );
}
