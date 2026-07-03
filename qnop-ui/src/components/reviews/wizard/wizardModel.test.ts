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

import { describe, expect, it } from 'vitest';
import { formatFileSize, titleFromFilename, validateDocumentFile } from './wizardModel';

function fileOf(name: string, type: string, sizeBytes: number): File {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: sizeBytes });
  return file;
}

describe('validateDocumentFile', () => {
  it('accepts a PDF under the limit', () => {
    expect(validateDocumentFile(fileOf('a.pdf', 'application/pdf', 1024), 50)).toBeNull();
  });

  it('accepts by .pdf extension when the browser reports no MIME type', () => {
    expect(validateDocumentFile(fileOf('Scan.PDF', '', 1024), 50)).toBeNull();
  });

  it('rejects non-PDF files', () => {
    expect(validateDocumentFile(fileOf('a.docx', 'application/msword', 10), 50)).toBe(
      'Only PDF documents are supported.',
    );
  });

  it('rejects files over the configured limit', () => {
    const tooBig = fileOf('big.pdf', 'application/pdf', 51 * 1024 * 1024);
    expect(validateDocumentFile(tooBig, 50)).toBe('The file exceeds the maximum size of 50 MB.');
  });
});

describe('titleFromFilename', () => {
  it('strips the pdf extension case-insensitively', () => {
    expect(titleFromFilename('NDA Acme Corp.pdf')).toBe('NDA Acme Corp');
    expect(titleFromFilename('contract.PDF')).toBe('contract');
  });

  it('leaves other names untouched', () => {
    expect(titleFromFilename('notes')).toBe('notes');
  });
});

describe('formatFileSize', () => {
  it('formats megabytes with one decimal', () => {
    expect(formatFileSize(2.4 * 1024 * 1024)).toBe('2.4 MB');
  });

  it('formats kilobytes and never shows 0 KB for tiny files', () => {
    expect(formatFileSize(412 * 1024)).toBe('412 KB');
    expect(formatFileSize(3)).toBe('1 KB');
  });
});
