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

/** Give the layers a good second of frames to mount before giving up. */
const MAX_RESOLVE_FRAMES = 60;

/**
 * Resolves a DOM id to its element for anchoring the focus overlay (issue
 * #291). The mark elements render inside the viewer's layer stack, typically
 * a tick AFTER the id becomes known (annotation refetch, page mount) — so the
 * lookup retries across animation frames, bounded, instead of reading once.
 */
export function useAnchorElement(id: string | null): HTMLElement | null {
  const [element, setElement] = useState<HTMLElement | null>(null);

  useEffect(() => {
    // All updates happen inside animation-frame callbacks (the DOM is the
    // external system being observed) — never synchronously in the effect.
    let frame = 0;
    let attempts = 0;
    const resolve = () => {
      const found = id ? document.getElementById(id) : null;
      if (found || !id || attempts >= MAX_RESOLVE_FRAMES) {
        setElement(found);
        return;
      }
      attempts += 1;
      frame = requestAnimationFrame(resolve);
    };
    frame = requestAnimationFrame(resolve);
    return () => cancelAnimationFrame(frame);
  }, [id]);

  return element;
}
