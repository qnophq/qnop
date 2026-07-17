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

const EM_DASH = '—';

/** Renders a single JSON value compactly for the audit detail cell. */
function formatValue(value: unknown): string {
  if (value === null || value === undefined) return EM_DASH;
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/**
 * Renders the audit event's raw jsonb `detail` (a JSON string) readably for the
 * audit table (issue #466). `workflow.transition` gets the emphasised old→new
 * form (`{ from, to }`); any other object detail becomes a compact
 * `key: value` list; a non-JSON payload (e.g. a bare id) is shown verbatim; and
 * an absent detail is an em dash. Best-effort by design — the event vocabulary
 * is open (ADR-0041), so unknown shapes still render something sensible.
 */
export function formatAuditDetail(eventType: string, detail: string | null | undefined): string {
  if (!detail) return EM_DASH;

  let parsed: unknown;
  try {
    parsed = JSON.parse(detail);
  } catch {
    // Not JSON (e.g. a bare annotation id) — show it as-is.
    return detail;
  }

  if (parsed === null || typeof parsed !== 'object') {
    return formatValue(parsed);
  }

  const obj = parsed as Record<string, unknown>;

  if (eventType === 'workflow.transition' && ('from' in obj || 'to' in obj)) {
    return `${formatValue(obj.from)} → ${formatValue(obj.to)}`;
  }

  const parts = Object.entries(obj).map(([key, value]) => `${key}: ${formatValue(value)}`);
  return parts.length > 0 ? parts.join(', ') : EM_DASH;
}
