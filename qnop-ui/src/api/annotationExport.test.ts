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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadAnnotationExport } from './annotationExport';
import { axiosInstance } from './config';

vi.mock('./config', () => ({
  axiosInstance: { get: vi.fn() },
}));

const objectUrl = 'blob:mock-url';

beforeEach(() => {
  vi.clearAllMocks();
  URL.createObjectURL = vi.fn(() => objectUrl);
  URL.revokeObjectURL = vi.fn();
});

function respond(disposition?: string) {
  vi.mocked(axiosInstance.get).mockResolvedValue({
    data: new Blob(['xlsx']),
    headers: disposition ? { 'content-disposition': disposition } : {},
  } as never);
}

describe('downloadAnnotationExport', () => {
  it('requests the export through the authenticated client, as a blob', async () => {
    respond();

    await downloadAnnotationExport('doc-1', 3);

    // A bare <a href> or window.open would carry no bearer token and simply 401.
    expect(axiosInstance.get).toHaveBeenCalledWith(
      '/documents/doc-1/annotations/export',
      expect.objectContaining({
        params: { version: 3 },
        responseType: 'blob',
      }),
    );
  });

  it('omits the version when there is none, letting the server pick the latest', async () => {
    respond();

    await downloadAnnotationExport('doc-1');

    expect(axiosInstance.get).toHaveBeenCalledWith(
      '/documents/doc-1/annotations/export',
      expect.objectContaining({ params: {} }),
    );
  });

  it('passes the wizard field selection and scope', async () => {
    respond();

    await downloadAnnotationExport('doc-1', 2, { fields: ['taskKey', 'status'], scope: 'open' });

    expect(axiosInstance.get).toHaveBeenCalledWith(
      '/documents/doc-1/annotations/export',
      expect.objectContaining({
        params: { version: 2, fields: ['taskKey', 'status'], scope: 'open' },
      }),
    );
  });

  it('leaves the default scope off the wire', async () => {
    respond();

    await downloadAnnotationExport('doc-1', 1, { fields: [], scope: 'all' });

    // "all" is the server's default; sending it would only make links noisier.
    expect(axiosInstance.get).toHaveBeenCalledWith(
      '/documents/doc-1/annotations/export',
      expect.objectContaining({ params: { version: 1 } }),
    );
  });

  it('saves under the filename the server chose', async () => {
    respond('attachment; filename="Vendor agreement-annotations.xlsx"');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    await downloadAnnotationExport('doc-1');

    expect(click).toHaveBeenCalled();
    expect(URL.createObjectURL).toHaveBeenCalled();
    // The object URL is released again — a download must not leak it.
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(objectUrl);
  });

  it('falls back to a sensible name when the header is missing', async () => {
    respond();
    let downloadName = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloadName = this.download;
    });

    await downloadAnnotationExport('doc-1');

    expect(downloadName).toBe('annotations.xlsx');
  });

  it('propagates a failure so the caller can surface it', async () => {
    vi.mocked(axiosInstance.get).mockRejectedValue(new Error('boom'));

    await expect(downloadAnnotationExport('doc-1')).rejects.toThrow('boom');
  });
});
