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

/**
 * What the export wizard offers and remembers (issue #547) — the data behind the
 * dialog, kept out of it so the choices are testable without rendering anything.
 */

import { FALLBACK_TIME_ZONE, isValidTimeZone } from '../../../utils/timezone';

export type ExportScope = 'all' | 'open' | 'resolved';

export interface ExportFormat {
  id: string;
  label: string;
  extension: string;
  /** One line on the format card: why you would pick this one. */
  hint: string;
  /**
   * What this format calls the selected fields. A spreadsheet has columns; a
   * report has details. The wizard asks the format rather than assuming, so a
   * new format brings its own vocabulary instead of inheriting Excel's.
   */
  fieldNoun: string;
  /** What switching the comment threads on actually does in this format. */
  commentsHint: string;
  /**
   * Whether the format can carry an image at all. Mirrors `AnnotationExportFormat`
   * on the server: Markdown and CSV are text, and offering a logo switch there
   * would be a control that silently does nothing.
   */
  supportsLogo: boolean;
  /** Not yet implemented — shown so the wizard tells the truth about what is coming. */
  planned?: boolean;
}

/**
 * The formats the wizard shows. Planned ones are listed on purpose: a user who
 * wonders whether HTML is coming gets an answer here instead of filing a
 * request, and shipping one flips a flag (issue #637). CSV and Markdown were
 * dropped (#636/#638): CSV is a strictly worse Excel for this data — no typed
 * dates, nowhere to put the comment threads — and Markdown duplicates what the
 * review UI already shows.
 *
 * <p>`planned` is a property of the release. Whether a shipped format can
 * actually be produced is a property of the *server* — PDF converts through an
 * out-of-process office suite (#639), which a given deployment may not have —
 * and that answer comes from `ServerConfig.exportFormats`, not from here.
 */
export const EXPORT_FORMATS: ExportFormat[] = [
  {
    id: 'xlsx',
    supportsLogo: true,
    label: 'Excel',
    extension: '.xlsx',
    hint: 'Sortable, filterable — for triage and reporting.',
    fieldNoun: 'columns',
    commentsHint:
      'A second sheet with the full text of every comment and who wrote it. In an anonymous review the authors stay pseudonymous here too.',
  },
  {
    id: 'docx',
    supportsLogo: true,
    label: 'Word',
    extension: '.docx',
    hint: 'A readable report — for meetings and sign-off.',
    fieldNoun: 'details',
    commentsHint:
      'Every reply in full, indented under the annotation it answers. In an anonymous review the authors stay pseudonymous here too.',
  },
  {
    id: 'html',
    supportsLogo: true,
    fieldNoun: 'sections',
    commentsHint: 'Comment threads are included.',
    label: 'HTML',
    extension: '.html',
    hint: 'Opens anywhere.',
    planned: true,
  },
  {
    id: 'pdf',
    supportsLogo: true,
    fieldNoun: 'details',
    commentsHint:
      'Every reply in full, indented under the annotation it answers. In an anonymous review the authors stay pseudonymous here too.',
    label: 'PDF',
    extension: '.pdf',
    hint: 'The Word report, fixed — for archiving and sign-off.',
  },
];

export interface ExportField {
  id: string;
  label: string;
  group: 'Identity' | 'Classification' | 'Content' | 'History';
  /** Cannot be switched off — a sheet of rows with nothing to name them is useless. */
  required?: boolean;
}

/** Mirrors `AnnotationExportColumn` on the server; ids must match, order need not. */
export const EXPORT_FIELDS: ExportField[] = [
  { id: 'taskKey', label: 'Task key (#)', group: 'Identity', required: true },
  { id: 'page', label: 'Page', group: 'Identity' },
  { id: 'author', label: 'Author', group: 'Identity' },
  { id: 'status', label: 'Status', group: 'Classification' },
  { id: 'type', label: 'Type', group: 'Classification' },
  { id: 'priority', label: 'Priority', group: 'Classification' },
  { id: 'placement', label: 'Placement state', group: 'Classification' },
  { id: 'summary', label: 'Summary', group: 'Content' },
  { id: 'replies', label: 'Replies', group: 'Content' },
  { id: 'created', label: 'Created', group: 'History' },
  { id: 'updated', label: 'Updated', group: 'History' },
];

export const FIELD_GROUPS: ExportField['group'][] = [
  'Identity',
  'Classification',
  'Content',
  'History',
];

export interface ExportDateFormat {
  id: string;
  label: string;
  /** What the choice actually produces — the only description anyone needs. */
  sample: string;
}

/**
 * Mirrors `ExportDateFormat` on the server; ids must match.
 *
 * <p>The sample is the label that matters: "European" means nothing until you
 * see `04.03.2026`, and `03/04/2026` is two different days depending on who
 * reads it. All times are UTC, which the wizard says once rather than repeating
 * per entry.
 */
export const EXPORT_DATE_FORMATS: ExportDateFormat[] = [
  { id: 'iso', label: 'ISO', sample: '2026-03-04 14:30' },
  { id: 'iso-seconds', label: 'ISO with seconds', sample: '2026-03-04 14:30:07' },
  { id: 'european', label: 'European', sample: '04.03.2026 14:30' },
  { id: 'us', label: 'US', sample: '03/04/2026 02:30 PM' },
  { id: 'date-only', label: 'Date only', sample: '2026-03-04' },
];

export interface ExportSettings {
  format: string;
  scope: ExportScope;
  fields: string[];
  /** Adds a second sheet with the full text of every comment, one row each. */
  includeComments: boolean;
  /** Places the operator's branding logo, where the format can carry one. */
  includeLogo: boolean;
  /** Which `EXPORT_DATE_FORMATS` entry every timestamp is written in. */
  dateFormat: string;
  /** The IANA zone those timestamps are expressed in. */
  timezone: string;
}

/**
 * Everything on, everything in — the state the wizard opens in the first time.
 *
 * @param timezone the reader's own zone, already resolved through the ADR-0041
 *   chain (profile → operator default → UTC). Passed in rather than resolved
 *   here, so the wizard and the rest of the app can never disagree about it.
 */
/**
 * Whether the server can actually produce a format.
 *
 * <p>A format the release ships is not automatically one this deployment can
 * make. When the server says nothing — an older build, or a request that has not
 * landed yet — everything shipped is assumed available, so a missing field never
 * takes a working format away.
 */
export function isFormatAvailable(format: ExportFormat, offered: string[] | undefined): boolean {
  if (format.planned) return false;
  return offered === undefined || offered.length === 0 || offered.includes(format.id);
}

export function defaultSettings(timezone: string = FALLBACK_TIME_ZONE): ExportSettings {
  return {
    format: 'xlsx',
    scope: 'all',
    fields: EXPORT_FIELDS.map((field) => field.id),
    includeComments: true,
    includeLogo: true,
    dateFormat: 'iso',
    timezone,
  };
}

const STORAGE_KEY = 'qnop-export-settings';

/**
 * The last configuration, so a second export of the same review is one click.
 *
 * <p>Anything unrecognised falls back to the defaults rather than throwing: the
 * field list will grow, and a stored selection from an older release must not
 * leave the wizard in a state the user cannot fix.
 */
export function loadSettings(timezone?: string): ExportSettings {
  const fallback = defaultSettings(timezone);
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<ExportSettings>;
    const known = new Set(EXPORT_FIELDS.map((field) => field.id));
    const fields = (parsed.fields ?? []).filter((id) => known.has(id));
    const shipped = EXPORT_FORMATS.find((f) => f.id === parsed.format && !f.planned);
    return {
      format: shipped ? shipped.id : fallback.format,
      scope: (['all', 'open', 'resolved'] as const).includes(parsed.scope as ExportScope)
        ? (parsed.scope as ExportScope)
        : fallback.scope,
      fields: fields.length > 0 ? fields : fallback.fields,
      includeComments: parsed.includeComments ?? fallback.includeComments,
      includeLogo: parsed.includeLogo ?? fallback.includeLogo,
      dateFormat: EXPORT_DATE_FORMATS.some((entry) => entry.id === parsed.dateFormat)
        ? (parsed.dateFormat as string)
        : fallback.dateFormat,
      // A zone the runtime no longer knows would break every timestamp in the
      // file, so an unusable stored value yields to the reader's own zone.
      timezone: isValidTimeZone(parsed.timezone) ? parsed.timezone : fallback.timezone,
    };
  } catch {
    return fallback;
  }
}

export function saveSettings(settings: ExportSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // Remembering the last export is a nicety; private-mode failures are fine.
  }
}

/** Required fields are always in, whatever the checkbox state says. */
export function effectiveFields(selected: string[]): string[] {
  const required = EXPORT_FIELDS.filter((field) => field.required).map((field) => field.id);
  return EXPORT_FIELDS.filter(
    (field) => required.includes(field.id) || selected.includes(field.id),
  ).map((field) => field.id);
}

/** How many annotations the current scope would export, given the board's counts. */
export function scopeCount(
  scope: ExportScope,
  counts: { all: number; open: number; resolved: number },
): number {
  return counts[scope];
}

/**
 * The prefilled base name: `<slug>-annotations`, mirroring `ExportFilename` on
 * the server.
 *
 * <p>Mirrored rather than fetched, because the wizard has to show the name
 * before any request is made. The two can only disagree cosmetically — the
 * server sanitizes whatever it receives, and the user sees and can edit the
 * value either way — which is why a round trip to stay in lockstep would cost
 * more than it is worth.
 */
export function defaultFileName(documentTitle: string | null | undefined): string {
  const slug = (documentTitle ?? '')
    .normalize('NFKD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80)
    .replace(/-+$/, '');
  return slug ? `${slug}-annotations` : 'annotations';
}
