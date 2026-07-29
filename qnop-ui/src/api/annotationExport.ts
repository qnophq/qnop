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
 * Downloads a review's annotations as a file (issues #547, #635).
 *
 * <p>It goes through the shared axios instance rather than a bare `<a href>` or
 * `window.open`, because the endpoint is bearer-authenticated: a plain browser
 * navigation carries no Authorization header and would simply 401 (see
 * `download.ts`).
 */
export async function downloadAnnotationExport(
  documentId: string,
  version?: number,
  options?: {
    fields?: string[];
    scope?: string;
    comments?: boolean;
    format?: string;
    logo?: boolean;
    dateFormat?: string;
    timezone?: string;
    fileName?: string;
  },
): Promise<void> {
  const response = await axiosInstance.get(`/documents/${documentId}/annotations/export`, {
    params: {
      ...(version ? { version } : {}),
      // Repeated params rather than one comma-joined value: a column id is a
      // plain identifier today, but the list format should not become a second
      // thing to escape if that ever changes.
      ...(options?.fields?.length ? { fields: options.fields } : {}),
      ...(options?.scope && options.scope !== 'all' ? { scope: options.scope } : {}),
      ...(options?.comments ? { comments: true } : {}),
      // Omitted for the default, so the links that shipped with #547 stay valid.
      ...(options?.format && options.format !== 'xlsx' ? { format: options.format } : {}),
      // Both omitted at their defaults, so a hand-written link stays short and
      // the server's default is the single source of what "unspecified" means.
      ...(options?.logo === false ? { logo: false } : {}),
      ...(options?.dateFormat && options.dateFormat !== 'iso'
        ? { dateFormat: options.dateFormat }
        : {}),
      // Always sent: the server's default is UTC, and the reader's own zone is
      // what the wizard showed them.
      ...(options?.timezone ? { timezone: options.timezone } : {}),
      // Omitted when empty, so the server's <slug>-annotations default applies
      // and there is one place that decides what "no name given" means.
      ...(options?.fileName ? { filename: options.fileName } : {}),
    },
    paramsSerializer: { indexes: null },
    responseType: 'blob',
  });

  const filename = filenameFrom(
    response.headers['content-disposition'] as string | undefined,
    `annotations.${options?.format ?? 'xlsx'}`,
  );
  saveBlob(response.data as Blob, filename);
}
