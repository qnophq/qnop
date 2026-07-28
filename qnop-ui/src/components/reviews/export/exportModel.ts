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

export type ExportScope = 'all' | 'open' | 'resolved';

export interface ExportFormat {
  id: string;
  label: string;
  extension: string;
  /** One line on the format card: why you would pick this one. */
  hint: string;
  /** Not yet implemented — shown so the wizard tells the truth about what is coming. */
  planned?: boolean;
}

/**
 * The formats the wizard shows. Planned ones are listed on purpose: a user who
 * wonders whether Word exists gets an answer here instead of filing a request,
 * and shipping one flips a flag (issues #635–#639).
 */
export const EXPORT_FORMATS: ExportFormat[] = [
  {
    id: 'xlsx',
    label: 'Excel',
    extension: '.xlsx',
    hint: 'Sortable, filterable — for triage and reporting.',
  },
  { id: 'docx', label: 'Word', extension: '.docx', hint: 'A readable report.', planned: true },
  {
    id: 'md',
    label: 'Markdown',
    extension: '.md',
    hint: 'For pull requests and wikis.',
    planned: true,
  },
  { id: 'html', label: 'HTML', extension: '.html', hint: 'Opens anywhere.', planned: true },
  { id: 'csv', label: 'CSV', extension: '.csv', hint: 'For other tools.', planned: true },
  { id: 'pdf', label: 'PDF', extension: '.pdf', hint: 'For archiving.', planned: true },
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

export interface ExportSettings {
  format: string;
  scope: ExportScope;
  fields: string[];
}

/** Everything on, everything in — the state the wizard opens in the first time. */
export function defaultSettings(): ExportSettings {
  return { format: 'xlsx', scope: 'all', fields: EXPORT_FIELDS.map((field) => field.id) };
}

const STORAGE_KEY = 'qnop-export-settings';

/**
 * The last configuration, so a second export of the same review is one click.
 *
 * <p>Anything unrecognised falls back to the defaults rather than throwing: the
 * field list will grow, and a stored selection from an older release must not
 * leave the wizard in a state the user cannot fix.
 */
export function loadSettings(): ExportSettings {
  const fallback = defaultSettings();
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
