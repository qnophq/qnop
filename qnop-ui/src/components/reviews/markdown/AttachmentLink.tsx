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

import { useState, type MouseEvent, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import { useTheme } from '@mui/material/styles';
import { Paperclip } from 'lucide-react';
import { axiosInstance } from '../../../api/config';
import { API_URL_PREFIX } from './attachmentSources';

interface AttachmentLinkProps {
  /** The app-relative target, e.g. `/api/v1/documents/{id}/attachments/{id}`. */
  href: string;
  /** The authored link label — normally the attachment's file name. */
  children: ReactNode;
}

/** The link label as plain text — doubles as the suggested download file name. */
function labelText(children: ReactNode): string {
  if (typeof children === 'string') return children;
  if (Array.isArray(children)) return children.filter((c) => typeof c === 'string').join('');
  return '';
}

/**
 * A comment link that points at the app's own attachment endpoint (issue
 * #446), rendered as a small file chip. The read path is authenticated, so a
 * plain href cannot carry the bearer; instead a click fetches the bytes
 * through the axios instance and hands them to the browser as a download
 * (non-image attachments are download-only by design — they never render in
 * the app origin).
 */
export function AttachmentLink({ href, children }: AttachmentLinkProps) {
  const theme = useTheme();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const download = async (event: MouseEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setFailed(false);
    try {
      const response = await axiosInstance.get<Blob>(href.slice(API_URL_PREFIX.length), {
        responseType: 'blob',
      });
      const url = URL.createObjectURL(response.data);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = labelText(children) || 'attachment';
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box
      component="a"
      href={href}
      onClick={download}
      data-testid="attachment-link"
      title={failed ? 'Download failed — click to retry' : 'Download attachment'}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        px: 0.75,
        py: 0.25,
        borderRadius: '6px',
        border: '1px solid',
        borderColor: failed ? 'error.main' : 'divider',
        bgcolor: theme.qnop.surface2,
        color: failed ? 'error.main' : 'text.primary',
        fontSize: '0.8125rem',
        lineHeight: 1.5,
        textDecoration: 'none',
        cursor: 'pointer',
        verticalAlign: 'baseline',
        transition: 'border-color 120ms ease',
        '&:hover': { borderColor: theme.qnop.brand.blue, textDecoration: 'none' },
        '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
      }}
    >
      {busy ? (
        <CircularProgress size={12} aria-label="Downloading" />
      ) : (
        <Paperclip size={12} aria-hidden />
      )}
      {children}
    </Box>
  );
}
