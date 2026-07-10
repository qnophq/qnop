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

import { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ImageOff } from 'lucide-react';
import { axiosInstance } from '../../../api/config';
import { API_URL_PREFIX } from './attachmentSources';

interface AttachmentImageProps {
  /** The app-relative source, e.g. `/api/v1/documents/{id}/attachments/{id}`. */
  src: string;
  alt: string;
}

/**
 * An <img> for the app's own attachment URLs (issue #446). The read path is
 * authenticated (review content is confidential — deliberately not public like
 * avatars), so a plain <img src> cannot carry the bearer; instead the bytes are
 * fetched through the axios instance (token + refresh handling attached) and
 * shown via a blob URL, which is revoked on unmount. The server's immutable
 * cache headers make refetches hit the HTTP cache.
 */
export function AttachmentImage({ src, alt }: AttachmentImageProps) {
  // Keyed by src: a stale result is simply ignored on render, so switching
  // sources needs no synchronous state reset inside the effect.
  const [result, setResult] = useState<{ src: string; url: string | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    (async () => {
      try {
        const response = await axiosInstance.get<Blob>(src.slice(API_URL_PREFIX.length), {
          responseType: 'blob',
        });
        if (cancelled) return;
        url = URL.createObjectURL(response.data);
        setResult({ src, url });
      } catch {
        if (!cancelled) setResult({ src, url: null });
      }
    })();
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [src]);

  const current = result?.src === src ? result : null;
  const failed = current !== null && current.url === null;
  const objectUrl = current?.url ?? null;

  if (failed) {
    return (
      <Stack
        direction="row"
        spacing={0.75}
        component="span"
        sx={{
          alignItems: 'center',
          px: 1,
          py: 0.75,
          my: 0.5,
          borderRadius: '6px',
          border: '1px dashed',
          borderColor: 'divider',
          color: 'text.disabled',
          display: 'inline-flex',
        }}
      >
        <ImageOff size={14} aria-hidden />
        <Typography component="span" variant="caption">
          {alt || 'Image unavailable'}
        </Typography>
      </Stack>
    );
  }

  if (!objectUrl) {
    // Reserved footprint while the bytes load, so the thread doesn't jump.
    return (
      <Box
        component="span"
        role="img"
        aria-label={alt || 'Loading image'}
        sx={{
          display: 'block',
          width: 180,
          maxWidth: '100%',
          height: 96,
          borderRadius: '6px',
          bgcolor: 'action.hover',
          my: 0.5,
        }}
      />
    );
  }

  return <img src={objectUrl} alt={alt} />;
}
