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

import type { BadgeTone } from '../components/admin/ToneBadge';

/** Human-facing metadata for one audit event type (issue #466). */
export interface AuditEventMeta {
  /** A short, plain-language name shown in place of the dotted machine type. */
  label: string;
  /** One sentence explaining what the event means, for the tooltip and the legend. */
  description: string;
  /** The semantic colour tone — grouped by domain so a scan reads at a glance. */
  tone: BadgeTone;
}

/**
 * The audit vocabulary (ADR-0042), each mapped to a readable label, a
 * plain-language description and a semantic colour. This is the single source
 * the audit table, the event filter and the legend all read from, so the
 * machine event types (`annotation.created`, …) never reach a human unexplained.
 * It spans both scopes: the per-document review trail and the SYSTEM-scope
 * operator stream (issue #524, ADR-0043) — e.g. storage-orphan cleanup.
 *
 * Colour grouping: annotations blue, resolutions/successes green, re-work and
 * destructive/system actions amber, failures red, document-level changes
 * neutral — so the trail is scannable by hue.
 */
export const AUDIT_EVENT_META: Record<string, AuditEventMeta> = {
  'annotation.created': {
    label: 'Annotation opened',
    description: 'A reviewer raised a new annotation — a comment or issue — on the document.',
    tone: 'blue',
  },
  'annotation.resolved': {
    label: 'Annotation resolved',
    description: 'An annotation was marked resolved: the point it raised was addressed.',
    tone: 'green',
  },
  'annotation.reopened': {
    label: 'Annotation reopened',
    description: 'A previously resolved annotation was reopened because it needed more work.',
    tone: 'amber',
  },
  'annotation.classified': {
    label: 'Annotation classified',
    description: 'An annotation was categorised — its type and/or priority were set.',
    tone: 'blue',
  },
  'placement.confirmed': {
    label: 'Placement confirmed',
    description: "An annotation's position on the page was confirmed on a document version.",
    tone: 'green',
  },
  'placement.reattached': {
    label: 'Placement re-attached',
    description:
      'An annotation was re-anchored to a new spot — e.g. after a new version shifted the text.',
    tone: 'amber',
  },
  'workflow.transition': {
    label: 'Status changed',
    description: 'The review moved to a new workflow status (e.g. Draft → In review).',
    tone: 'blue',
  },
  'document.due_date.changed': {
    label: 'Due date changed',
    description: "The review's due date was set, changed or cleared.",
    tone: 'neutral',
  },
  'extraction.succeeded': {
    label: 'Text extracted',
    description: "An uploaded version's text was extracted successfully and is ready to review.",
    tone: 'green',
  },
  'extraction.failed': {
    label: 'Extraction failed',
    description:
      'Extracting the text from an uploaded version failed; the reason is shown in the details.',
    tone: 'red',
  },
  'storage.orphan.deleted': {
    label: 'Orphaned object deleted',
    description:
      'An unreferenced object was deleted from storage during a consistency cleanup (issue #523); the object key is shown in the details.',
    tone: 'amber',
  },
};

/** The event types in a sensible display order — drives the filter and the legend. */
export const AUDIT_EVENT_TYPES = Object.keys(AUDIT_EVENT_META);

/** Metadata for an event type, with a safe fallback for any future/unknown type. */
export function auditEventMeta(eventType: string): AuditEventMeta {
  return (
    AUDIT_EVENT_META[eventType] ?? {
      label: eventType,
      description: 'A recorded review event.',
      tone: 'neutral',
    }
  );
}

const WORKFLOW_STATE_LABELS: Record<string, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In review',
  CHANGES_REQUESTED: 'Changes requested',
  FINALIZED: 'Finalized',
  CANCELLED: 'Cancelled',
};

/** A workflow state as shown to a human; unknown (enterprise) states pass through. */
export function humanizeWorkflowState(state: string): string {
  return WORKFLOW_STATE_LABELS[state] ?? state;
}

/** Turns an enum-ish token (`CHANGES_REQUESTED`, `HIGH`) into `Changes requested` / `High`. */
export function humanizeToken(token: string): string {
  const spaced = token.replace(/_/g, ' ').toLowerCase().trim();
  return spaced ? spaced.charAt(0).toUpperCase() + spaced.slice(1) : token;
}
