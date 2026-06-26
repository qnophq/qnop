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

import { useEffect } from 'react';

/**
 * Points the document's `<link rel="icon">` at `url` (the branding logomark) at runtime,
 * restoring the previous icon when the URL changes or the caller unmounts (issue #154). A null/empty
 * URL is a no-op, so the static favicon from index.html remains. SPA-only.
 */
export function useFavicon(url: string | null | undefined) {
  useEffect(() => {
    if (!url) {
      return;
    }
    const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    if (!link) {
      return;
    }
    const previous = link.getAttribute('href');
    link.setAttribute('href', url);
    return () => {
      if (previous === null) {
        link.removeAttribute('href');
      } else {
        link.setAttribute('href', previous);
      }
    };
  }, [url]);
}
