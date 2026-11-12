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

/**
 * The page's Content-Security-Policy nonce (issue #913, ADR-0040 amendment).
 *
 * The SPA's policy allows `<style>` elements only when they carry the nonce of
 * the response that served `index.html`. The server writes that nonce into the
 * `<meta property="csp-nonce" nonce="…">` Vite's `html.cspNonce` emits (Vite's
 * own CSS loader reads the same tag); everything that injects a `<style>` at
 * runtime — Emotion, CodeMirror, react-easy-crop, emoji-mart — takes it from
 * here.
 */

/** What the build leaves in `index.html` for the server to replace. */
export const CSP_NONCE_PLACEHOLDER = '__CSP_NONCE__';

const META_SELECTOR = 'meta[property="csp-nonce"]';

/**
 * The nonce of this page, or `undefined` when there is none — no meta tag, or
 * an `index.html` served without substitution (the placeholder is not a nonce).
 *
 * Reads the `nonce` IDL property first: once a policy is in force, browsers
 * hide the attribute value from the DOM and only the property still has it.
 */
export function readCspNonce(doc: Document = document): string | undefined {
  const meta = doc.querySelector<HTMLMetaElement>(META_SELECTOR);
  if (!meta) return undefined;
  const value = meta.nonce || meta.getAttribute('nonce') || '';
  if (value === '' || value === CSP_NONCE_PLACEHOLDER) return undefined;
  return value;
}

/**
 * Stamps `nonce` on every `<style>` under `root` that has none. For libraries
 * that build their `<style>` without nonce support: call it after the library
 * created its elements and BEFORE they are connected to the document — the
 * policy is checked when a style element is connected, not afterwards.
 */
export function stampCspNonce(root: ParentNode, nonce: string | undefined): void {
  if (!nonce) return;
  root.querySelectorAll('style').forEach((style) => {
    if (!style.nonce && !style.getAttribute('nonce')) style.setAttribute('nonce', nonce);
  });
}
