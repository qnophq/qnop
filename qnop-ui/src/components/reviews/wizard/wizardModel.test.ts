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
  launchChecklist,
  launchReadiness,
  formatFileSize,
  suggestSlug,
  titleFromFilename,
  validateDocumentFile,
  validateSlug,
} from './wizardModel';

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
describe('suggestSlug', () => {
  it('kebab-cases the title', () => {
    expect(suggestSlug('NDA Acme Corp')).toBe('nda-acme-corp');
  });

  it('strips diacritics and collapses punctuation runs', () => {
    expect(suggestSlug('Übergabe — Q3/2026 (final!)')).toBe('ubergabe-q3-2026-final');
  });

  it('trims leading and trailing separators', () => {
    expect(suggestSlug('  --Hello World--  ')).toBe('hello-world');
  });

  it('caps at 64 characters without leaving a dangling hyphen', () => {
    const suggested = suggestSlug(`${'a'.repeat(63)} tail`);
    expect(suggested).toHaveLength(63);
    expect(suggested.endsWith('-')).toBe(false);
  });

  it('returns empty for titles with no usable characters', () => {
    expect(suggestSlug('!!! ???')).toBe('');
  });
});

describe('validateSlug', () => {
  it('accepts a kebab-case slug and the empty (optional) value', () => {
    expect(validateSlug('nda-acme-corp')).toBeNull();
    expect(validateSlug('')).toBeNull();
  });

  it('rejects out-of-range lengths', () => {
    expect(validateSlug('ab')).toBe('The slug must be 3–64 characters long.');
    expect(validateSlug('x'.repeat(65))).toBe('The slug must be 3–64 characters long.');
  });

  it('rejects uppercase, underscores, and hyphen runs', () => {
    const message = 'Only lowercase letters, digits and single hyphens are allowed.';
    expect(validateSlug('Nda-Acme')).toBe(message);
    expect(validateSlug('has_underscore')).toBe(message);
    expect(validateSlug('double--hyphen')).toBe(message);
    expect(validateSlug('-leading')).toBe(message);
  });

  it('rejects UUID-shaped slugs, which would shadow id routes', () => {
    expect(validateSlug('123e4567-e89b-12d3-a456-426614174000')).toBe(
      'The slug must not look like a document id.',
    );
  });
});

describe('launchChecklist / launchReadiness (issue #469)', () => {
  const base = {
    hasFile: false,
    title: '',
    slug: '',
    reviewerCount: 0,
    dueAt: null as string | null,
    startImmediately: false,
  };

  it('starts empty and climbs with the essentials', () => {
    expect(launchReadiness(launchChecklist(base))).toBe(0);
    const essentials = launchChecklist({ ...base, hasFile: true, title: 'Q3 contract' });
    expect(launchReadiness(essentials)).toBe(70);
    expect(essentials.filter((i) => !i.optional).every((i) => i.done)).toBe(true);
  });

  it('tops up to 100% only with every bonus item', () => {
    const all = launchChecklist({
      hasFile: true,
      title: 'Q3 contract',
      slug: 'q3-contract',
      reviewerCount: 2,
      dueAt: '2027-01-01T00:00:00Z',
      startImmediately: true,
    });
    expect(launchReadiness(all)).toBe(100);
    expect(all.find((i) => i.label === 'Crew invited')?.detail).toBe('2 reviewers');
  });

  it('bonus items alone never reach the launch threshold', () => {
    const onlyBonus = launchChecklist({
      ...base,
      slug: 'x',
      reviewerCount: 1,
      dueAt: '2027-01-01T00:00:00Z',
      startImmediately: true,
    });
    expect(launchReadiness(onlyBonus)).toBe(30);
    expect(onlyBonus.filter((i) => !i.optional).some((i) => !i.done)).toBe(true);
  });
});
