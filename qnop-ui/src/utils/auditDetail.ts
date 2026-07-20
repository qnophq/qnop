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

import { humanizeToken, humanizeWorkflowState } from './auditEvents';

const EM_DASH = '—';

/** Renders a single JSON value compactly for the fallback detail rendering. */
function formatValue(value: unknown): string {
  if (value === null || value === undefined) return EM_DASH;
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function transitionSide(value: unknown): string {
  return value === null || value === undefined ? EM_DASH : humanizeWorkflowState(String(value));
}

function dueDateChange(from: unknown, to: unknown, formatDate: (iso: string) => string): string {
  const before = from === null || from === undefined ? null : formatDate(String(from));
  const after = to === null || to === undefined ? null : formatDate(String(to));
  if (before === null && after === null) return EM_DASH;
  if (before === null) return `Set to ${after}`;
  if (after === null) return `Cleared (was ${before})`;
  return `${before} → ${after}`;
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
 * list, since the vocabulary is open.
 */
export function formatAuditDetail(
  eventType: string,
  detail: string | null | undefined,
  formatDate: (iso: string) => string = (iso) => iso,
): string {
  if (!detail) return EM_DASH;

  let parsed: unknown;
  try {
    parsed = JSON.parse(detail);
  } catch {
    // Not JSON (e.g. a bare id) — show it as-is.
    return detail;
  }

  if (parsed === null || typeof parsed !== 'object') {
    return formatValue(parsed);
  }

  const obj = parsed as Record<string, unknown>;

  switch (eventType) {
    case 'annotation.created':
    case 'annotation.resolved':
    case 'annotation.reopened':
    case 'extraction.succeeded':
      // The event label already conveys the meaning; the payload is only an
      // internal id, which is never shown.
      return EM_DASH;
    case 'annotation.classified': {
      const bits: string[] = [];
      if (obj.type !== null && obj.type !== undefined) bits.push(humanizeToken(String(obj.type)));
      if (obj.priority !== null && obj.priority !== undefined) {
        bits.push(`${humanizeToken(String(obj.priority))} priority`);
      }
      return bits.length > 0 ? `As ${bits.join(' · ')}` : EM_DASH;
    }
    case 'placement.confirmed':
    case 'placement.reattached':
      return obj.versionNumber !== null && obj.versionNumber !== undefined
        ? `On version ${formatValue(obj.versionNumber)}`
        : EM_DASH;
    case 'workflow.transition':
      if ('from' in obj || 'to' in obj) {
        return `${transitionSide(obj.from)} → ${transitionSide(obj.to)}`;
      }
      break;
    case 'document.due_date.changed':
      return dueDateChange(obj.from, obj.to, formatDate);
    case 'extraction.failed':
      return obj.reason !== null && obj.reason !== undefined
        ? `Reason: ${formatValue(obj.reason)}`
        : EM_DASH;
    case 'storage.orphan.deleted':
      return obj.key !== null && obj.key !== undefined ? `Object ${formatValue(obj.key)}` : EM_DASH;
    default:
      break;
  }

  // Open-vocabulary fallback: a compact key: value list.
  const parts = Object.entries(obj).map(([key, value]) => `${key}: ${formatValue(value)}`);
  return parts.length > 0 ? parts.join(', ') : EM_DASH;
}
