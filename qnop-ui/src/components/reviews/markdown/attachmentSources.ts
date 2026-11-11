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
 * True when a target points into the app's API but is not an attachment URL (issue #911). Such
 * targets render link-less: they are never fetched with the bearer token, and the viewer's browser
 * issues no request on the author's behalf.
 */
export function isOtherAppApiUrl(target: string | undefined): boolean {
  return (
    typeof target === 'string' &&
    target.toLowerCase().startsWith('/api/') &&
    !isAppAttachmentUrl(target)
  );
}

/** Characters that are unsafe or meaningless in a downloaded file name. */
// eslint-disable-next-line no-control-regex
const UNSAFE_FILENAME_CHARS = /[\u0000-\u001f\u007f/\\:*?"<>|]/g;

/** Caps the suggested name well below the 255-byte limit of common file systems. */
const MAX_FILENAME_LENGTH = 200;

/**
 * Reduces a server-supplied file name to a safe download name (issue #911): replaces path
 * separators, control and reserved characters, drops leading dots/whitespace (no hidden or
 * relative names) and caps the length. Returns `null` when nothing usable remains.
 */
export function sanitizeFilename(raw: string): string | null {
  const cleaned = raw
    .replace(UNSAFE_FILENAME_CHARS, '_')
    .replace(/^[\s.]+/, '')
    .trim()
    .slice(0, MAX_FILENAME_LENGTH);
  return /[^_]/.test(cleaned) ? cleaned : null;
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
  const extended = /filename\*\s*=\s*([^;]+)/i.exec(header);
  const decoded = extended ? decodeExtValue(extended[1].trim()) : null;
  if (decoded !== null) return sanitizeFilename(decoded);
  const quoted = /filename\s*=\s*"((?:[^"\\]|\\.)*)"/i.exec(header);
  if (quoted) return sanitizeFilename(quoted[1].replace(/\\(.)/g, '$1'));
  const token = /filename\s*=\s*([^;\s]+)/i.exec(header);
  return token ? sanitizeFilename(token[1]) : null;
}
