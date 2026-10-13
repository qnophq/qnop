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

import type { TFunction } from 'i18next';
import { humanizeToken, humanizeWorkflowState, rawPhrase, type AuditPhrase } from './auditEvents';

const EM_DASH = '—';

/** The "nothing to show" detail — an em dash. */
const NONE: AuditPhrase = { key: 'detail.none' };

/** Renders a single JSON value compactly for the fallback detail rendering. */
function formatValue(value: unknown): string {
  if (value === null || value === undefined) return EM_DASH;
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function transitionSide(value: unknown): AuditPhrase {
  return value === null || value === undefined ? NONE : humanizeWorkflowState(String(value));
}

/**
 * Renders a purge run (issue #577): the counts, then the titles that went. This
 * is the only surviving trace of those reviews — their own audit trail was
 * deleted with them — so the titles are shown, not just how many.
 */
function purgeSummary(obj: Record<string, unknown>): AuditPhrase {
  const reviews = typeof obj.reviews === 'number' ? obj.reviews : null;
  const objects = typeof obj.storageObjects === 'number' ? obj.storageObjects : null;
  const titles = Array.isArray(obj.purged)
    ? obj.purged
        .map((entry) =>
          entry !== null && typeof entry === 'object'
            ? (entry as Record<string, unknown>).title
            : null,
        )
        .filter((title): title is string => typeof title === 'string' && title.length > 0)
    : [];

  const bits: AuditPhrase[] = [];
  if (reviews !== null) bits.push({ key: 'detail.purge.reviews', values: { count: reviews } });
  if (objects !== null) {
    bits.push({ key: 'detail.purge.storageObjects', values: { count: objects } });
  }
  const truncated = typeof obj.truncated === 'number' ? obj.truncated : 0;
  if (titles.length > 0) {
    const listed = titles.join(', ');
    bits.push(
      truncated > 0
        ? { key: 'detail.purge.more', values: { titles: listed, remaining: truncated } }
        : rawPhrase(listed),
    );
  }
  return bits.length > 0 ? { join: bits, separator: ' · ' } : NONE;
}

function dueDateChange(
  from: unknown,
  to: unknown,
  formatDate: (iso: string) => string,
): AuditPhrase {
  const before = from === null || from === undefined ? null : formatDate(String(from));
  const after = to === null || to === undefined ? null : formatDate(String(to));
  if (before === null && after === null) return NONE;
  if (before === null) return { key: 'detail.dueDate.set', values: { date: after as string } };
  if (after === null) return { key: 'detail.dueDate.cleared', values: { date: before } };
  return { key: 'detail.change', values: { from: before, to: after } };
}

/**
 * Renders an audit event's raw jsonb `detail` as a short, plain-language phrase
 * for the audit table (issue #466) — never a raw UUID (ADR-0042). Each known
 * event type gets a purpose-built rendering: a workflow transition reads as
 * `Draft → In review`, a placement as `On version 3`, a due-date change as
 * `Set to <date>` / `<date> → <date>` (dates via the caller's zone-aware
 * formatter), an extraction failure as its reason, a storage-orphan deletion as
 * its object key. Events whose meaning is fully carried by their label (a
 * raised/resolved/reopened annotation, whose only payload is an internal id)
 * render an em dash. Any unknown shape still degrades to a compact `key: value`
 * list, since the vocabulary is open. The result is an {@link AuditPhrase} —
 * catalog keys plus values — that {@link renderAuditPhrase} turns into text.
 */
export function formatAuditDetail(
  eventType: string,
  detail: string | null | undefined,
  formatDate: (iso: string) => string = (iso) => iso,
): AuditPhrase {
  if (!detail) return NONE;

  let parsed: unknown;
  try {
    parsed = JSON.parse(detail);
  } catch {
    // Not JSON (e.g. a bare id) — show it as-is.
    return rawPhrase(detail);
  }

  // A JSON null is as empty as no detail at all — same dash, no copy button.
  if (parsed === null) return NONE;
  if (typeof parsed !== 'object') {
    return rawPhrase(formatValue(parsed));
  }

  const obj = parsed as Record<string, unknown>;

  switch (eventType) {
    case 'annotation.created':
    case 'annotation.resolved':
    case 'annotation.reopened':
    case 'annotation.dismissed':
    case 'extraction.succeeded':
      // The event label already conveys the meaning; the payload is only an
      // internal id, which is never shown.
      return NONE;
    case 'annotation.classified': {
      const bits: AuditPhrase[] = [];
      if (obj.type !== null && obj.type !== undefined) {
        bits.push(rawPhrase(humanizeToken(String(obj.type))));
      }
      if (obj.priority !== null && obj.priority !== undefined) {
        bits.push({
          key: 'detail.priority',
          values: { priority: humanizeToken(String(obj.priority)) },
        });
      }
      return bits.length > 0
        ? { key: 'detail.classified', values: { parts: { join: bits, separator: ' · ' } } }
        : NONE;
    }
    case 'annotation.auto_closed':
      return typeof obj.reason === 'string'
        ? { key: 'detail.reason', values: { reason: humanizeToken(String(obj.reason)) } }
        : NONE;
    case 'placement.confirmed':
    case 'placement.reattached':
    case 'placement.repositioned':
      return obj.versionNumber !== null && obj.versionNumber !== undefined
        ? { key: 'detail.onVersion', values: { version: formatValue(obj.versionNumber) } }
        : NONE;
    case 'workflow.transition':
      if ('from' in obj || 'to' in obj) {
        return {
          key: 'detail.change',
          values: { from: transitionSide(obj.from), to: transitionSide(obj.to) },
        };
      }
      break;
    case 'document.due_date.changed':
      return dueDateChange(obj.from, obj.to, formatDate);
    case 'extraction.failed':
      return obj.reason !== null && obj.reason !== undefined
        ? { key: 'detail.reason', values: { reason: formatValue(obj.reason) } }
        : NONE;
    case 'storage.orphan.deleted':
      return obj.key !== null && obj.key !== undefined
        ? { key: 'detail.object', values: { objectKey: formatValue(obj.key) } }
        : NONE;
    case 'review.archived':
      return obj.outcome !== null && obj.outcome !== undefined
        ? { key: 'detail.was', values: { state: humanizeWorkflowState(String(obj.outcome)) } }
        : NONE;
    case 'review.purged':
      return purgeSummary(obj);
    default:
      break;
  }

  // Open-vocabulary fallback: a compact key: value list of the raw payload — technical, so
  // it is shown verbatim rather than translated.
  const parts = Object.entries(obj).map(([key, value]) => `${key}: ${formatValue(value)}`);
  return parts.length > 0 ? rawPhrase(parts.join(', ')) : NONE;
}

/** True for the em-dash detail — nothing worth copying. */
export function isEmptyAuditDetail(phrase: AuditPhrase): boolean {
  return phrase === NONE;
}

/** Resolves an {@link AuditPhrase} against the `audit` catalog — nested phrases first. */
export function renderAuditPhrase(phrase: AuditPhrase, t: TFunction<'audit'>): string {
  if ('join' in phrase) {
    return phrase.join.map((part) => renderAuditPhrase(part, t)).join(phrase.separator);
  }
  const values = Object.fromEntries(
    Object.entries(phrase.values ?? {}).map(([name, value]) => [
      name,
      typeof value === 'object' ? renderAuditPhrase(value, t) : value,
    ]),
  );
  return t(phrase.key, values);
}
