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
import { filenameFrom, saveBlob } from './download';

/**
 * Fetches one attachment and saves it (issue #635 follow-up).
 *
 * <p>The API endpoint is bearer-authenticated, which is why an export cannot
 * simply link to it: a browser following that link sends no token and gets a
 * 401 instead of a file. The link goes to a page in the app, and the page calls
 * this.
 *
 * @returns the saved filename, so the page can name what it just delivered
 */
export async function downloadAttachment(
  documentId: string,
  attachmentId: string,
): Promise<string> {
  const response = await axiosInstance.get(`/documents/${documentId}/attachments/${attachmentId}`, {
    responseType: 'blob',
  });
  const filename = filenameFrom(
    response.headers['content-disposition'] as string | undefined,
    'attachment',
  );
  saveBlob(response.data as Blob, filename);
  return filename;
}
