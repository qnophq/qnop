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

import { useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import type { SxProps, Theme } from '@mui/material/styles';

interface BrandLogoProps {
  /** Effective asset URL from `config.branding` (carries the cache-busting `?v=` token). */
  url: string | null | undefined;
  /** Accessible name for the logo image. */
  alt: string;
  /** Rendered when there is no URL or the image fails to load. */
  fallback: ReactNode;
  sx?: SxProps<Theme>;
}

/**
 * Renders a branding asset from `config.branding` with a robust fallback (issue #154): a missing
 * URL or a broken/expired image shows `fallback` instead, mirroring {@link UserAvatar}'s
 * image-with-fallback. The `?v=` cache-busting token in the URL means a fresh upload reloads the
 * image; changing the URL resets a prior load failure (the React "adjust state during render"
 * pattern, avoiding a setState-in-effect cascade).
 */
export function BrandLogo({ url, alt, fallback, sx }: BrandLogoProps) {
  const [failed, setFailed] = useState(false);
  const [lastUrl, setLastUrl] = useState(url);
  if (url !== lastUrl) {
    setLastUrl(url);
    setFailed(false);
  }

  if (!url || failed) {
    return <>{fallback}</>;
  }

  return (
    <Box
      component="img"
      src={url}
      alt={alt}
      onError={() => setFailed(true)}
      sx={{ display: 'block', objectFit: 'contain', ...sx }}
    />
  );
}
