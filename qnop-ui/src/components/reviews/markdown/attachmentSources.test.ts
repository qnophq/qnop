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
import {
  filenameFromContentDisposition,
  isAppAttachmentUrl,
  isOtherAppApiUrl,
  sanitizeFilename,
} from './attachmentSources';

const DOC = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const ATT = '0190A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A01';
const VALID = `/api/v1/documents/${DOC}/attachments/${ATT}`;

describe('isAppAttachmentUrl (#911)', () => {
  it('accepts exactly the attachment route', () => {
    expect(isAppAttachmentUrl(VALID)).toBe(true);
  });

  it.each([
    undefined,
    '',
    `${VALID}/`,
    `${VALID}/extra`,
    `${VALID}?download=1`,
    `${VALID}#frag`,
    `/api/v1/documents/${DOC}/attachments/../../users/me`,
    `/api/v1/documents/${DOC}/attachments/%2e%2e`,
    `/api/v1/documents/${DOC}/attachments/%2E%2E`,
    `/api/v1/documents/${DOC}/./attachments/${ATT}`,
    `/api/v1/documents/not-a-uuid/attachments/${ATT}`,
    `/api/v2/documents/${DOC}/attachments/${ATT}`,
    `//other.example/api/v1/documents/${DOC}/attachments/${ATT}`,
    `https://other.example${VALID}`,
    `/api/v1/documents/${DOC}/attachments`,
    '/api/v1/users/me',
  ])('rejects %s', (target) => {
    expect(isAppAttachmentUrl(target)).toBe(false);
  });
});

describe('isOtherAppApiUrl (#911)', () => {
  it('flags API paths that are not the attachment route', () => {
    expect(isOtherAppApiUrl('/api/v1/users/me')).toBe(true);
    expect(isOtherAppApiUrl(`${VALID}/../x`)).toBe(true);
    expect(isOtherAppApiUrl('/API/v1/x')).toBe(true);
  });

  it.each([
    `${window.location.origin}/api/v1/users/me`,
    `//${window.location.host}/api/v1/users/me`,
    '../api/v1/users/me',
    './api/v1/users/me',
    '\\api\\v1\\users\\me',
    '/api',
    '/x/../api/v1/users/me',
  ])('resolves other same-origin spellings of the API: %s', (target) => {
    expect(isOtherAppApiUrl(target)).toBe(true);
  });

  it('leaves attachment and external targets alone', () => {
    expect(isOtherAppApiUrl(VALID)).toBe(false);
    expect(isOtherAppApiUrl('https://example.com/api/v1/x')).toBe(false);
    expect(isOtherAppApiUrl(undefined)).toBe(false);
    expect(isOtherAppApiUrl('/apiary')).toBe(false);
    expect(isOtherAppApiUrl('/reviews/x')).toBe(false);
  });
});

describe('sanitizeFilename (#911)', () => {
  it('keeps an ordinary name', () => {
    expect(sanitizeFilename('Report Q3 (final).pdf')).toBe('Report Q3 (final).pdf');
  });

  it('replaces separators, reserved and control characters', () => {
    expect(sanitizeFilename('a/b\\c:d*e?f"g<h>i|j\u0000k.txt')).toBe('a_b_c_d_e_f_g_h_i_j_k.txt');
  });

  it('drops leading dots and whitespace', () => {
    expect(sanitizeFilename('  ..hidden.txt')).toBe('hidden.txt');
  });

  it('replaces bidi and C1 controls', () => {
    expect(sanitizeFilename('report‮fdp.exe')).toBe('report_fdp.exe');
    expect(sanitizeFilename('a⁦b‏c\u0085d.txt')).toBe('a_b_c_d.txt');
  });

  it('drops trailing dots and whitespace', () => {
    expect(sanitizeFilename('notes.txt. . ')).toBe('notes.txt');
  });

  it('defuses reserved Windows device names', () => {
    expect(sanitizeFilename('CON')).toBe('_CON');
    expect(sanitizeFilename('nul.txt')).toBe('_nul.txt');
    expect(sanitizeFilename('com1.pdf')).toBe('_com1.pdf');
    expect(sanitizeFilename('console.txt')).toBe('console.txt');
  });

  it('caps the length', () => {
    expect(sanitizeFilename('x'.repeat(500))).toHaveLength(200);
  });

  it('returns null when nothing usable remains', () => {
    expect(sanitizeFilename('')).toBeNull();
    expect(sanitizeFilename('...')).toBeNull();
    expect(sanitizeFilename('///')).toBeNull();
  });
});

describe('filenameFromContentDisposition (#911)', () => {
  it('reads a quoted filename', () => {
    expect(filenameFromContentDisposition('attachment; filename="minutes.pdf"')).toBe(
      'minutes.pdf',
    );
  });

  it('reads an unquoted token filename', () => {
    expect(filenameFromContentDisposition('inline; filename=shot.png')).toBe('shot.png');
  });

  it('prefers the UTF-8 extended filename', () => {
    expect(
      filenameFromContentDisposition(
        `attachment; filename="Bericht.pdf"; filename*=UTF-8''Pr%C3%BCfbericht.pdf`,
      ),
    ).toBe('Prüfbericht.pdf');
  });

  it('falls back to the plain filename when the extended value is malformed', () => {
    expect(
      filenameFromContentDisposition(`attachment; filename="a.pdf"; filename*=UTF-8''%E0%A4%A`),
    ).toBe('a.pdf');
  });

  it('treats parameter syntax inside a quoted value as part of the value', () => {
    expect(
      filenameFromContentDisposition(`attachment; filename="a; filename*=UTF-8''other.exe; b.pdf"`),
    ).toBe(`a; filename_=UTF-8''other.exe; b.pdf`);
  });

  it('reads the server-emitted UTF-8 extended form on its own', () => {
    expect(
      filenameFromContentDisposition(
        `attachment; filename*=UTF-8''Pr%C3%BCfbericht%20%E2%82%AC.pdf`,
      ),
    ).toBe('Prüfbericht €.pdf');
  });

  it('never yields a path from the server value', () => {
    const name = filenameFromContentDisposition('attachment; filename="../../notes.txt"');
    expect(name).not.toBeNull();
    expect(name).not.toMatch(/[/\\]/);
    expect(name?.startsWith('.')).toBe(false);
  });

  it('returns null without a header or filename', () => {
    expect(filenameFromContentDisposition(null)).toBeNull();
    expect(filenameFromContentDisposition('attachment')).toBeNull();
  });
});
