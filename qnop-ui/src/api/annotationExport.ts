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

import { axiosInstance } from './config';

/** Pulled out of `Content-Disposition`, so the saved file keeps the server's name. */
function filenameFrom(disposition: string | undefined, fallback: string): string {
  if (!disposition) return fallback;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8) return decodeURIComponent(utf8[1]);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  return plain ? plain[1] : fallback;
}

/**
 * Downloads a review's annotations as an Excel workbook (issue #547).
 *
 * <p>It goes through the shared axios instance rather than a bare `<a href>` or
 * `window.open`, because the endpoint is bearer-authenticated: a plain browser
 * navigation carries no Authorization header and would simply 401. So the file
 * arrives as a blob and is handed to the browser through an object URL.
 */
export async function downloadAnnotationExport(
  documentId: string,
  version?: number,
): Promise<void> {
  const response = await axiosInstance.get(`/documents/${documentId}/annotations/export`, {
    params: version ? { version } : undefined,
    responseType: 'blob',
  });

  const filename = filenameFrom(
    response.headers['content-disposition'] as string | undefined,
    'annotations.xlsx',
  );
  const url = URL.createObjectURL(response.data as Blob);
  try {
    const link = window.document.createElement('a');
    link.href = url;
    link.download = filename;
    window.document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    // Revoking immediately is safe — the click has already handed the blob over.
    URL.revokeObjectURL(url);
  }
}
