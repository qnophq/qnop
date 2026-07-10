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

import { useMemo } from 'react';
import { isAxiosError } from 'axios';
import { axiosInstance } from '../../../api/config';
import type { Notify } from '../../admin/layout/useToast';

/** What the composer needs to build the Markdown reference (issue #446). */
export interface UploadedAttachment {
  id: string;
  url: string;
  fileName: string;
  /** The server-sniffed type — decides image vs. link syntax in the body. */
  contentType: string;
}

function uploadErrorMessage(error: unknown): string {
  // The cap is admin-configurable (upload.attachment_max_file_size_mb), so the
  // message names no number.
  if (isAxiosError(error) && error.response?.status === 413) {
    return 'The file is too large.';
  }
  return 'Could not upload the file.';
}

/**
 * The composer's file uploader (issue #446): posts the file to the document's
 * attachment endpoint (multipart — deliberately outside the generated client,
 * ADR-0028) and resolves to the Markdown reference data. Failures surface as a
 * toast here and re-throw, so the composer can roll its placeholder back.
 * Returns undefined without a document scope — the composer then hides its
 * attach affordances.
 */
export function useCommentAttachmentUpload(
  documentId: string | undefined,
  notify: Notify,
): ((file: File) => Promise<UploadedAttachment>) | undefined {
  return useMemo(() => {
    if (!documentId) return undefined;
    return async (file: File): Promise<UploadedAttachment> => {
      const form = new FormData();
      form.append('file', file, file.name || 'image');
      try {
        const response = await axiosInstance.post<UploadedAttachment>(
          `/documents/${documentId}/attachments`,
          form,
        );
        return response.data;
      } catch (error: unknown) {
        notify(uploadErrorMessage(error), 'error');
        throw error;
      }
    };
  }, [documentId, notify]);
}
