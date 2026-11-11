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

/** The SPA's API origin prefix — attachment URLs in comment bodies carry it (issue #446). */
export const API_URL_PREFIX = '/api/v1';

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';

/**
 * The one route the renderer may fetch with the viewer's bearer token (issue #911): exactly
 * `/api/v1/documents/<uuid>/attachments/<uuid>`. Anchored and built only from hex digits and
 * hyphens, so dot segments (plain or percent-encoded), extra segments, query strings and
 * fragments can never match.
 */
const ATTACHMENT_ROUTE = new RegExp(`^${API_URL_PREFIX}/documents/${UUID}/attachments/${UUID}$`);

/** True when a Markdown image/link target is exactly an app attachment URL (issues #446, #911). */
export function isAppAttachmentUrl(target: string | undefined): target is string {
  return typeof target === 'string' && ATTACHMENT_ROUTE.test(target);
}

/**
 * True when a target resolves into the app's own API but is not an attachment URL (issue #911).
 * The target is resolved against the current page first, so absolute, protocol-relative,
 * relative, backslash and dot-segment spellings of a same-origin `/api` path are all caught. Such
 * targets render link-less: they are never fetched (with or without the bearer token) and never
 * become a clickable link.
 */
export function isOtherAppApiUrl(target: string | undefined): boolean {
  if (typeof target !== 'string' || isAppAttachmentUrl(target)) return false;
  let resolved: URL;
  try {
    resolved = new URL(target, window.location.href);
  } catch {
    return false;
  }
  const path = resolved.pathname.toLowerCase();
  return (
    resolved.origin === window.location.origin && (path === '/api' || path.startsWith('/api/'))
  );
}

/**
 * Characters that are unsafe or misleading in a downloaded file name: C0/C1 controls, DEL, the
 * Unicode bidi controls (which can make a name display with a fake extension), path separators
 * and the characters Windows reserves.
 */
const UNSAFE_FILENAME_CHARS =
  // eslint-disable-next-line no-control-regex
  /[\u0000-\u001f\u007f-\u009f‎‏‪-‮⁦-⁩/\\:*?"<>|]/g;

/** Windows device names, reserved with or without an extension. */
const RESERVED_DEVICE_NAME = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\.|$)/i;

/** Caps the suggested name well below the 255-byte limit of common file systems. */
const MAX_FILENAME_LENGTH = 200;

/**
 * Reduces a server-supplied file name to a safe download name (issue #911): replaces path
 * separators, control, bidi and reserved characters, drops leading and trailing dots/whitespace
 * (no hidden, relative or silently-truncated names), defuses Windows device names and caps the
 * length. Returns `null` when nothing usable remains.
 */
export function sanitizeFilename(raw: string): string | null {
  const cleaned = raw
    .replace(UNSAFE_FILENAME_CHARS, '_')
    .slice(0, MAX_FILENAME_LENGTH)
    .replace(/^[\s.]+/, '')
    .replace(/[\s.]+$/, '');
  if (!/[^_]/.test(cleaned)) return null;
  return RESERVED_DEVICE_NAME.test(cleaned) ? `_${cleaned}` : cleaned;
}

/** Decodes an RFC 5987 `charset'lang'value` extended parameter; `null` when malformed. */
function decodeExtValue(value: string): string | null {
  const match = /^([^']*)'[^']*'(.*)$/.exec(value);
  if (!match || match[1].toLowerCase() !== 'utf-8') return null;
  try {
    return decodeURIComponent(match[2]);
  } catch {
    return null;
  }
}

/**
 * The sanitised file name from a `Content-Disposition` header (issue #911) — the UTF-8
 * `filename*` (RFC 6266 / 5987) preferred over the plain `filename`. Downloads are named by the
 * server's attachment metadata, never by the author-controlled link label.
 */
export function filenameFromContentDisposition(header: string | null | undefined): string | null {
  if (!header) return null;
  const params = dispositionParams(header);
  const extended = params.get('filename*');
  const decoded = extended !== undefined ? decodeExtValue(extended) : null;
  if (decoded !== null) return sanitizeFilename(decoded);
  const plain = params.get('filename');
  return plain !== undefined ? sanitizeFilename(plain) : null;
}

/**
 * Splits a `Content-Disposition` value into its parameters (lower-cased names → values), honouring
 * quoted strings: a `;` or `name=` inside quotes is part of the value, never a parameter boundary.
 * The first occurrence of a parameter wins; the leading disposition type is skipped.
 */
function dispositionParams(header: string): Map<string, string> {
  const params = new Map<string, string>();
  const segments: string[] = [];
  let current = '';
  let quoted = false;
  for (let i = 0; i < header.length; i++) {
    const ch = header[i];
    if (quoted && ch === '\\' && i + 1 < header.length) {
      current += ch + header[++i];
    } else if (ch === '"') {
      quoted = !quoted;
      current += ch;
    } else if (ch === ';' && !quoted) {
      segments.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  segments.push(current);
  for (const segment of segments.slice(1)) {
    const eq = segment.indexOf('=');
    if (eq < 0) continue;
    const name = segment.slice(0, eq).trim().toLowerCase();
    const value = unquote(segment.slice(eq + 1).trim());
    if (name && !params.has(name)) params.set(name, value);
  }
  return params;
}

/** Strips the surrounding quotes of an RFC 7230 quoted-string and resolves its escapes. */
function unquote(value: string): string {
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1).replace(/\\(.)/g, '$1');
  }
  return value;
}
