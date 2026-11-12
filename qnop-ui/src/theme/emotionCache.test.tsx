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

import { CacheProvider } from '@emotion/react';
import Box from '@mui/material/Box';
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { createEmotionCache } from './emotionCache';

function setMeta(nonce: string | null): void {
  document.head.querySelectorAll('meta[property="csp-nonce"]').forEach((m) => m.remove());
  if (nonce === null) return;
  const meta = document.createElement('meta');
  meta.setAttribute('property', 'csp-nonce');
  meta.setAttribute('nonce', nonce);
  document.head.append(meta);
}

describe('createEmotionCache', () => {
  afterEach(() => {
    setMeta(null);
    document.head.querySelectorAll('style[data-emotion]').forEach((s) => s.remove());
  });

  it('hands the page nonce to Emotion', () => {
    setMeta('bootstrap-nonce');
    const cache = createEmotionCache();
    expect(cache.nonce).toBe('bootstrap-nonce');
    expect(cache.key).toBe('css');
  });

  it('every style element MUI inserts carries the nonce', () => {
    setMeta('styled-nonce');
    render(
      <CacheProvider value={createEmotionCache()}>
        <Box sx={{ color: 'red' }}>styled</Box>
      </CacheProvider>,
    );
    const styles = document.head.querySelectorAll('style[data-emotion]');
    expect(styles.length).toBeGreaterThan(0);
    styles.forEach((style) => expect(style.getAttribute('nonce')).toBe('styled-nonce'));
  });

  it('runs without a nonce when the page has none', () => {
    expect(createEmotionCache().nonce).toBeUndefined();
  });
});
