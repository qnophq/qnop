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

/** Pure logic of the new-review wizard — kept DOM-free for direct unit testing. */

const BYTES_PER_MB = 1024 * 1024;

/**
 * Client-side pre-check of the chosen file (the backend re-validates and is
 * authoritative). PDF-only mirrors the Community scope of the vertical slice.
 */
export function validateDocumentFile(file: File, maxSizeMb: number): string | null {
  const isPdf = file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
  if (!isPdf) return 'Only PDF documents are supported.';
  if (file.size > maxSizeMb * BYTES_PER_MB) {
    return `The file exceeds the maximum size of ${maxSizeMb} MB.`;
  }
  return null;
}

/** Default review title derived from the file name (extension stripped). */
export function titleFromFilename(filename: string): string {
  return filename.replace(/\.pdf$/i, '').trim();
}

/** Human-readable file size ("2.4 MB", "412 KB"). */
export function formatFileSize(bytes: number): string {
  if (bytes >= BYTES_PER_MB) return `${(bytes / BYTES_PER_MB).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
