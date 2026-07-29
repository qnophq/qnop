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
import { Link as RouterLink, useParams } from 'react-router';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import { Check, Download, Paperclip, TriangleAlert } from 'lucide-react';
import { downloadAttachment } from '../../api/attachments';

type State =
  | { phase: 'idle' }
  | { phase: 'running' }
  | { phase: 'done'; fileName: string }
  | { phase: 'failed'; message: string };

/**
 * The landing page an exported report's attachment link points at (issue #635).
 *
 * <p>The attachment endpoint is bearer-authenticated, so a report could not link
 * to it directly: a browser following that link sends no token and lands on a
 * 401 rather than a file. This page sits in front of it. Being inside the
 * protected routes means an unauthenticated visitor is sent to the login form
 * and returned here afterwards — the redirect that already exists for every
 * other deep link — and only then is the file fetched, with the token attached.
 *
 * <p>The download is not started automatically. A page that fires a download the
 * moment it opens gives no chance to see what arrived, and a browser that blocks
 * it leaves the visitor on a screen that appears to have done nothing. One
 * button, and the result stated plainly.
 */
export function AttachmentDownloadPage() {
  const { documentId, attachmentId } = useParams<{ documentId: string; attachmentId: string }>();
  const [state, setState] = useState<State>({ phase: 'idle' });

  const start = async () => {
    if (!documentId || !attachmentId) return;
    setState({ phase: 'running' });
    try {
      const fileName = await downloadAttachment(documentId, attachmentId);
      setState({ phase: 'done', fileName });
    } catch {
      // 404 covers both "gone" and "not yours" — the API refuses to distinguish
      // them, so neither does this page.
      setState({
        phase: 'failed',
        message:
          'This file is not available. It may have been deleted, or the review may not be shared with you.',
      });
    }
  };

  return (
    <Stack sx={{ alignItems: 'center', py: { xs: 6, md: 10 } }}>
      <Box
        sx={{
          width: '100%',
          maxWidth: 460,
          p: 4,
          borderRadius: 3,
          border: '1px solid',
          borderColor: 'divider',
          bgcolor: (t) => t.qnop.surface2,
          textAlign: 'center',
        }}
      >
        <Box
          sx={{
            width: 56,
            height: 56,
            mx: 'auto',
            mb: 2,
            display: 'grid',
            placeItems: 'center',
            borderRadius: '50%',
            bgcolor: (t) => alpha(t.palette.primary.main, 0.1),
            color: 'primary.main',
          }}
        >
          {state.phase === 'done' ? <Check size={26} /> : <Paperclip size={24} />}
        </Box>

        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 800, mb: 0.5 }}>
          {state.phase === 'done' ? 'Saved' : 'Review attachment'}
        </Typography>

        <Typography sx={{ fontSize: 14, color: 'text.secondary', mb: 3 }}>
          {state.phase === 'done'
            ? state.fileName
            : 'This file was attached to an annotation. Download it to your device.'}
        </Typography>

        {state.phase === 'failed' && (
          <Stack
            direction="row"
            spacing={1}
            sx={{
              alignItems: 'flex-start',
              textAlign: 'left',
              p: 1.5,
              mb: 2,
              borderRadius: 2,
              border: '1px solid',
              borderColor: 'error.main',
              color: 'error.main',
            }}
          >
            <TriangleAlert size={16} style={{ flexShrink: 0, marginTop: 2 }} />
            <Typography sx={{ fontSize: 13 }}>{state.message}</Typography>
          </Stack>
        )}

        <Button
          variant="contained"
          size="large"
          fullWidth
          disabled={state.phase === 'running' || !documentId || !attachmentId}
          startIcon={
            state.phase === 'running' ? (
              <CircularProgress size={16} color="inherit" />
            ) : (
              <Download size={17} />
            )
          }
          onClick={() => void start()}
        >
          {state.phase === 'done' ? 'Download again' : 'Download'}
        </Button>

        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 2 }}>
          <Link component={RouterLink} to={`/reviews/${documentId ?? ''}`} underline="hover">
            Open the review
          </Link>
        </Typography>
      </Box>
    </Stack>
  );
}
