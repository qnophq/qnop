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

import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';
import {
  BoxSelect,
  ChevronDown,
  ChevronUp,
  GitCompareArrows,
  TextCursor,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import type { DocumentVersionSummary } from '../../../api/generated';
import { ExtractionStatus } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';

export type ViewerTool = 'text' | 'region';

export const MIN_ZOOM = 0.5;
export const MAX_ZOOM = 3;
const ZOOM_STEP = 0.25;

interface ViewerToolbarProps {
  versions: DocumentVersionSummary[];
  currentVersion: number;
  onVersionChange: (versionNumber: number) => void;
  extractionStatus?: ExtractionStatus;
  /** Zero-based page from the viewer's scroll spy. */
  currentPage: number;
  pageCount: number;
  onNavigateToPage: (pageIndex: number) => void;
  tool: ViewerTool;
  onToolChange: (tool: ViewerTool) => void;
  textToolAvailable: boolean;
  canAnnotate: boolean;
  zoom: number;
  onZoomChange: (zoom: number) => void;
  /** Link to the version comparison (#252); undefined hides the button (fewer than two extracted versions). */
  compareHref?: string;
}

/**
 * The viewer's control strip: version switcher, page navigation (fed by the
 * viewer's scroll spy), extraction cue, annotation tool toggle (text quote
 * vs. universal region, ADR-0009) and zoom. Sits as its own row above the
 * document pane, so the tools stay reachable while the pages scroll.
 */
export function ViewerToolbar({
  versions,
  currentVersion,
  onVersionChange,
  extractionStatus,
  currentPage,
  pageCount,
  onNavigateToPage,
  tool,
  onToolChange,
  textToolAvailable,
  canAnnotate,
  zoom,
  onZoomChange,
  compareHref,
}: ViewerToolbarProps) {
  return (
    <Paper
      variant="outlined"
      sx={{
        px: 1.5,
        py: 1,
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        flexWrap: 'wrap',
      }}
    >
      <TextField
        select
        size="small"
        label="Version"
        value={String(currentVersion)}
        onChange={(event) => onVersionChange(Number(event.target.value))}
        sx={{ minWidth: 110 }}
      >
        {versions.map((version) => (
          <MenuItem key={version.versionNumber} value={String(version.versionNumber)}>
            v{version.versionNumber}
          </MenuItem>
        ))}
      </TextField>
      {compareHref && (
        <Tooltip title="Compare versions">
          <IconButton
            size="small"
            aria-label="Compare versions"
            component={RouterLink}
            to={compareHref}
          >
            <GitCompareArrows size={16} />
          </IconButton>
        </Tooltip>
      )}

      <Stack direction="row" spacing={0.25} sx={{ alignItems: 'center' }}>
        <IconButton
          size="small"
          aria-label="Previous page"
          disabled={currentPage <= 0}
          onClick={() => onNavigateToPage(currentPage - 1)}
        >
          <ChevronUp size={16} />
        </IconButton>
        <Typography
          variant="body2"
          color="text.secondary"
          aria-live="polite"
          sx={{ fontVariantNumeric: 'tabular-nums', px: 0.5, whiteSpace: 'nowrap' }}
        >
          Page {Math.min(currentPage + 1, pageCount)} / {pageCount}
        </Typography>
        <IconButton
          size="small"
          aria-label="Next page"
          disabled={currentPage >= pageCount - 1}
          onClick={() => onNavigateToPage(currentPage + 1)}
        >
          <ChevronDown size={16} />
        </IconButton>
      </Stack>

      {extractionStatus === ExtractionStatus.Pending && (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <CircularProgress size={14} />
          <Typography variant="body2" color="text.secondary">
            Processing document…
          </Typography>
        </Stack>
      )}
      {extractionStatus === ExtractionStatus.Failed && (
        <ToneBadge tone="red" label="Extraction failed" />
      )}

      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', ml: 'auto' }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={tool}
          onChange={(_event, next: ViewerTool | null) => next && onToolChange(next)}
          disabled={!canAnnotate}
          aria-label="Annotation tool"
        >
          <ToggleButton value="text" disabled={!textToolAvailable} aria-label="Select text">
            <Tooltip title="Select text to annotate a quote">
              <TextCursor size={16} />
            </Tooltip>
          </ToggleButton>
          <ToggleButton value="region" aria-label="Draw region">
            <Tooltip title="Draw a rectangle to annotate a region">
              <BoxSelect size={16} />
            </Tooltip>
          </ToggleButton>
        </ToggleButtonGroup>

        <Divider orientation="vertical" flexItem />

        <IconButton
          size="small"
          aria-label="Zoom out"
          disabled={zoom <= MIN_ZOOM}
          onClick={() => onZoomChange(Math.max(MIN_ZOOM, zoom - ZOOM_STEP))}
        >
          <ZoomOut size={16} />
        </IconButton>
        <Tooltip title="Reset zoom to fit width">
          <Typography
            component="button"
            variant="body2"
            color="text.secondary"
            aria-label="Reset zoom to fit width"
            onClick={() => onZoomChange(1)}
            sx={{
              minWidth: 44,
              textAlign: 'center',
              fontVariantNumeric: 'tabular-nums',
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              font: 'inherit',
              borderRadius: 1,
              '&:focus-visible': (theme) => ({ outline: 'none', boxShadow: theme.qnop.focusRing }),
            }}
          >
            {Math.round(zoom * 100)}%
          </Typography>
        </Tooltip>
        <IconButton
          size="small"
          aria-label="Zoom in"
          disabled={zoom >= MAX_ZOOM}
          onClick={() => onZoomChange(Math.min(MAX_ZOOM, zoom + ZOOM_STEP))}
        >
          <ZoomIn size={16} />
        </IconButton>
      </Stack>
    </Paper>
  );
}
