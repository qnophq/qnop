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

import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { ZoomIn, ZoomOut } from 'lucide-react';

export const MIN_ZOOM = 0.5;
export const MAX_ZOOM = 3;
const ZOOM_STEP = 0.25;

interface ZoomControlsProps {
  /** 1 = fit width; the pages track their container times this factor. */
  zoom: number;
  onZoomChange: (zoom: number) => void;
}

/**
 * The shared zoom triplet of the document surfaces (review viewer and version
 * comparison): step out, click the percentage to reset to fit-width, step in.
 */
export function ZoomControls({ zoom, onZoomChange }: ZoomControlsProps) {
  return (
    <>
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
    </>
  );
}
