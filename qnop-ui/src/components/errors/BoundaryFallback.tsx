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

import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { RotateCcw, TriangleAlert } from 'lucide-react';

interface BoundaryFallbackProps {
  /** Retry callback from the enclosing {@link ErrorBoundary}. */
  onRetry: () => void;
  /** Short label for what failed, e.g. "The document viewer". */
  title?: string;
  /** Compact variant for a small pane (viewer/panel); default is roomier. */
  dense?: boolean;
}

/**
 * The default scoped fallback for an {@link ErrorBoundary} (issue #331): an
 * in-brand "something went wrong" card with a retry, sized to the pane it
 * replaces rather than taking over the page.
 */
export function BoundaryFallback({
  onRetry,
  title = 'Something went wrong',
  dense = false,
}: BoundaryFallbackProps) {
  return (
    <Paper
      variant="outlined"
      role="alert"
      sx={{
        display: 'grid',
        placeItems: 'center',
        textAlign: 'center',
        p: dense ? 3 : 5,
        minHeight: dense ? 160 : 240,
        height: '100%',
      }}
    >
      <Stack spacing={1.25} sx={{ alignItems: 'center', maxWidth: 360 }}>
        <Box sx={{ color: 'error.main', display: 'flex' }}>
          <TriangleAlert size={dense ? 24 : 32} aria-hidden />
        </Box>
        <Typography variant={dense ? 'subtitle2' : 'h6'} component="p">
          {title}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          This part of the page ran into an unexpected error. You can try again, or reload the page
          if it keeps happening.
        </Typography>
        <Button
          size="small"
          variant="outlined"
          startIcon={<RotateCcw size={15} />}
          onClick={onRetry}
          sx={{ mt: 0.5 }}
        >
          Try again
        </Button>
      </Stack>
    </Paper>
  );
}
