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

import { afterEach, describe, expect, it } from 'vitest';
import { CSP_NONCE_PLACEHOLDER, readCspNonce, stampCspNonce } from './cspNonce';

function setMeta(nonce: string | null): void {
  document.head.querySelectorAll('meta[property="csp-nonce"]').forEach((m) => m.remove());
  if (nonce === null) return;
  const meta = document.createElement('meta');
  meta.setAttribute('property', 'csp-nonce');
  meta.setAttribute('nonce', nonce);
  document.head.append(meta);
}

describe('readCspNonce', () => {
  afterEach(() => setMeta(null));

  it('returns the nonce the server wrote into the meta tag', () => {
    setMeta('q7Lk0a+/Zx==');
    expect(readCspNonce()).toBe('q7Lk0a+/Zx==');
  });

  it('returns undefined when the page has no nonce meta tag', () => {
    expect(readCspNonce()).toBeUndefined();
  });

  it('treats the unsubstituted build placeholder as no nonce', () => {
    setMeta(CSP_NONCE_PLACEHOLDER);
    expect(readCspNonce()).toBeUndefined();
  });

  it('treats an empty nonce as no nonce', () => {
    setMeta('');
    expect(readCspNonce()).toBeUndefined();
  });

  it('prefers the nonce property, which browsers keep when they hide the attribute', () => {
    setMeta('');
    const meta = document.querySelector<HTMLMetaElement>('meta[property="csp-nonce"]');
    Object.defineProperty(meta, 'nonce', { value: 'from-property', configurable: true });
    expect(readCspNonce()).toBe('from-property');
  });
});

describe('stampCspNonce', () => {
  it('stamps style elements that carry no nonce and leaves the rest alone', () => {
    const host = document.createElement('div');
    const shadow = host.attachShadow({ mode: 'open' });
    const bare = document.createElement('style');
    const own = document.createElement('style');
    own.setAttribute('nonce', 'theirs');
    shadow.append(bare, own);

    stampCspNonce(shadow, 'ours');

    expect(bare.getAttribute('nonce')).toBe('ours');
    expect(own.getAttribute('nonce')).toBe('theirs');
  });

  it('does nothing without a nonce', () => {
    const root = document.createElement('div');
    const style = document.createElement('style');
    root.append(style);

    stampCspNonce(root, undefined);

    expect(style.hasAttribute('nonce')).toBe(false);
  });
});
