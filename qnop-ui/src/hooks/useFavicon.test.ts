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

import { beforeEach, describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useFavicon } from './useFavicon';

function iconHref(): string | null {
  return document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.getAttribute('href') ?? null;
}

beforeEach(() => {
  document.querySelectorAll('link[rel="icon"]').forEach((el) => el.remove());
  const link = document.createElement('link');
  link.setAttribute('rel', 'icon');
  link.setAttribute('type', 'image/svg+xml');
  link.setAttribute('href', '/favicon.svg');
  document.head.appendChild(link);
});

describe('useFavicon', () => {
  it('points the icon link at the given URL', () => {
    renderHook(() => useFavicon('/brand/logomark.svg?v=abc'));

    expect(iconHref()).toBe('/brand/logomark.svg?v=abc');
  });

  it('keeps the static favicon when the URL is absent', () => {
    renderHook(() => useFavicon(null));

    expect(iconHref()).toBe('/favicon.svg');
  });

  it('restores the previous icon on unmount', () => {
    const { unmount } = renderHook(() => useFavicon('/brand/logomark.svg?v=abc'));
    expect(iconHref()).toBe('/brand/logomark.svg?v=abc');

    unmount();

    expect(iconHref()).toBe('/favicon.svg');
  });
});
