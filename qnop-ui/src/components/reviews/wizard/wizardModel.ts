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

import type { SupportedFormat } from '../../../api/generated';

const BYTES_PER_MB = 1024 * 1024;

const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

/**
 * What this server takes, as the pieces the upload UI needs.
 *
 * <p>Derived from `ServerConfig.supportedFormats` rather than hardcoded, because
 * it is a property of the deployment: Word needs an out-of-process converter
 * (issue #343, ADR-0010), and a server without one rejects a DOCX with 415. When
 * the config has not arrived yet the answer is PDF — offering a format that may
 * be refused is worse than briefly offering one fewer.
 */
export interface AcceptedUploads {
  /** The `accept` attribute of a file input. */
  accept: string;
  /** How the dropzone names the formats, e.g. `PDF or Word`. */
  label: string;
  /** Whether a picked file passes the client-side type check. */
  matches: (file: File) => boolean;
}

export function acceptedUploads(
  formats: SupportedFormat[] | undefined,
  mediaTypes: string[] | undefined = undefined,
): AcceptedUploads {
  const word = (formats ?? []).includes('DOCX');
  // Extension-contributed types (issue #601): whatever the server advertises beyond
  // the built-in pair widens the filter — the server-side gate stays authoritative.
  const extra = (mediaTypes ?? []).filter(
    (type) => type !== 'application/pdf' && type !== DOCX_MIME,
  );
  const matchesPdf = (file: File) => file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
  const matchesWord = (file: File) => file.type === DOCX_MIME || /\.docx$/i.test(file.name);
  const base = word ? `application/pdf,.pdf,${DOCX_MIME},.docx` : 'application/pdf,.pdf';
  return {
    accept: extra.length ? `${base},${extra.join(',')}` : base,
    label: word ? 'PDF or Word' : 'PDF',
    matches: (file) => matchesPdf(file) || (word && matchesWord(file)) || extra.includes(file.type),
  };
}

/**
 * Client-side pre-check of the chosen file (the backend re-validates and is
 * authoritative).
 */
export function validateDocumentFile(
  file: File,
  maxSizeMb: number,
  accepted: AcceptedUploads,
): string | null {
  if (!accepted.matches(file)) return `Only ${accepted.label} documents are supported.`;
  if (file.size > maxSizeMb * BYTES_PER_MB) {
    return `The file exceeds the maximum size of ${maxSizeMb} MB.`;
  }
  return null;
}

/** Default review title derived from the file name (extension stripped). */
export function titleFromFilename(filename: string): string {
  return filename.replace(/\.(pdf|docx)$/i, '').trim();
}

export const SLUG_MIN_LENGTH = 3;
export const SLUG_MAX_LENGTH = 64;
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
// A slug must never be UUID-shaped — routes resolve those segments as document ids.
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * Suggests a slug from the review title (issue #411): lowercased, diacritics
 * stripped, everything non-alphanumeric collapsed into single hyphens, capped
 * at the server's maximum length. May return '' when the title has no usable
 * characters — the slug stays optional either way.
 */
export function suggestSlug(title: string): string {
  return title
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, SLUG_MAX_LENGTH)
    .replace(/-+$/, '');
}

/**
 * Client-side mirror of the server's slug rules (issue #411; the backend is
 * authoritative). Empty means "no slug" and is valid.
 */
export function validateSlug(slug: string): string | null {
  if (slug === '') return null;
  if (slug.length < SLUG_MIN_LENGTH || slug.length > SLUG_MAX_LENGTH) {
    return `The slug must be ${SLUG_MIN_LENGTH}–${SLUG_MAX_LENGTH} characters long.`;
  }
  if (!SLUG_PATTERN.test(slug)) {
    return 'Only lowercase letters, digits and single hyphens are allowed.';
  }
  if (UUID_SHAPE.test(slug)) {
    return 'The slug must not look like a document id.';
  }
  return null;
}

/** Human-readable file size ("2.4 MB", "412 KB"). */
export function formatFileSize(bytes: number): string {
  if (bytes >= BYTES_PER_MB) return `${(bytes / BYTES_PER_MB).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/** One line of the launch checklist (issue #469): what, whether it counts, whether it's done. */
export interface LaunchItem {
  label: string;
  done: boolean;
  /** Optional items sweeten readiness but never block the launch. */
  optional: boolean;
  detail?: string;
}

/** The wizard's live launch checklist, derived purely from the form state. */
export function launchChecklist(state: {
  hasFile: boolean;
  title: string;
  slug: string;
  reviewerCount: number;
  dueAt: string | null;
  startImmediately: boolean;
}): LaunchItem[] {
  return [
    { label: 'Document aboard', done: state.hasFile, optional: false },
    { label: 'Title set', done: state.title.trim() !== '', optional: false },
    { label: 'Friendly link', done: state.slug.trim() !== '', optional: true },
    {
      label: 'Crew invited',
      done: state.reviewerCount > 0,
      optional: true,
      detail:
        state.reviewerCount > 0
          ? `${state.reviewerCount} reviewer${state.reviewerCount === 1 ? '' : 's'}`
          : undefined,
    },
    { label: 'Deadline locked', done: state.dueAt !== null, optional: true },
    {
      label: 'Ignition on create',
      done: state.startImmediately,
      optional: true,
      detail: state.startImmediately ? 'starts immediately' : 'starts later, manually',
    },
  ];
}

/**
 * Launch readiness in percent: required items carry the weight (70%), the
 * optional extras top it up — 100% needs everything, but launch is possible
 * from 70%.
 */
export function launchReadiness(items: LaunchItem[]): number {
  const required = items.filter((item) => !item.optional);
  const optional = items.filter((item) => item.optional);
  const requiredShare =
    required.length === 0 ? 0.7 : (required.filter((i) => i.done).length / required.length) * 0.7;
  const optionalShare =
    optional.length === 0 ? 0.3 : (optional.filter((i) => i.done).length / optional.length) * 0.3;
  return Math.round((requiredShare + optionalShare) * 100);
}
