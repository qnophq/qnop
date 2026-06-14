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

import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Container from '@mui/material/Container';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';

/**
 * Phase 0 application shell. The edition chip is a static placeholder — it will
 * be driven by GET /api/edition once the backend server lands in Phase 1. The
 * document review workspace (PDF.js viewer + annotation layer) mounts where the
 * placeholder surface is.
 */
export function App() {
  return (
    <Box sx={{ minHeight: '100dvh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="static" color="primary" elevation={0} component="header">
        <Toolbar sx={{ gap: 1.5 }}>
          <Typography
            variant="h6"
            component="span"
            sx={{ fontWeight: 700, letterSpacing: '0.02em' }}
          >
            qnop
          </Typography>
          <Typography variant="body2" component="span" sx={{ opacity: 0.7 }}>
            Qualified Notes on Papers
          </Typography>
          <Box sx={{ flexGrow: 1 }} />
          <Chip label="Community" size="small" color="secondary" variant="filled" />
        </Toolbar>
      </AppBar>

      <Container component="main" maxWidth="lg" sx={{ flexGrow: 1, py: { xs: 4, md: 8 } }}>
        <Stack spacing={3}>
          <Box>
            <Typography variant="overline" color="text.secondary">
              Phase 0 · Skeleton
            </Typography>
            <Typography variant="h1" component="h1" sx={{ maxWidth: '18ch' }}>
              Dokumenten-Review im Browser
            </Typography>
            <Typography variant="body1" color="text.secondary" sx={{ mt: 2, maxWidth: '60ch' }}>
              Reviewer markieren Zeilen, kommentieren und durchlaufen einen koordinierten
              Review-Workflow. Der Arbeitsbereich entsteht in Phase 1.
            </Typography>
          </Box>

          <Paper
            variant="outlined"
            aria-label="Review-Arbeitsbereich (Platzhalter)"
            sx={{
              borderStyle: 'dashed',
              borderColor: 'divider',
              bgcolor: 'background.default',
              minHeight: 320,
              display: 'grid',
              placeItems: 'center',
              p: 4,
            }}
          >
            <Typography color="text.secondary">
              Hier mountet in Phase 1 der PDF.js-Viewer mit Annotations-Layer.
            </Typography>
          </Paper>
        </Stack>
      </Container>
    </Box>
  );
}
