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

import type { ParseKeys } from 'i18next';
import type { BadgeTone } from '../components/admin/ToneBadge';

/** A key in the `audit` catalog (issue #836) — the utils name text, the views translate it. */
export type AuditKey = ParseKeys<'audit'>;

/**
 * A piece of audit text as data: a catalog key with its interpolation values (a value may
 * itself be a phrase, e.g. a translated workflow state inside a transition), or several phrases
 * joined by a separator. Resolved at render by `renderAuditPhrase`, so it follows a language
 * switch.
 */
export type AuditPhrase =
  | { key: AuditKey; values?: Record<string, string | number | AuditPhrase> }
  | { join: AuditPhrase[]; separator: string };

/** Verbatim text — a server value or a technical token that is not translated. */
export function rawPhrase(text: string): AuditPhrase {
  return { key: 'detail.raw', values: { text } };
}

/**
 * Human-facing metadata for one audit event type (issue #466). Label and description are
 * `audit` catalog keys; both take the raw event type as `type`, which only the fallback for an
 * unknown event shows.
 */
export interface AuditEventMeta {
  /** A short, plain-language name shown in place of the dotted machine type. */
  labelKey: AuditKey;
  /** One sentence explaining what the event means, for the tooltip and the legend. */
  descriptionKey: AuditKey;
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
    labelKey: 'events.annotationCreated.label',
    descriptionKey: 'events.annotationCreated.description',
    tone: 'blue',
  },
  'annotation.resolved': {
    labelKey: 'events.annotationResolved.label',
    descriptionKey: 'events.annotationResolved.description',
    tone: 'green',
  },
  'annotation.reopened': {
    labelKey: 'events.annotationReopened.label',
    descriptionKey: 'events.annotationReopened.description',
    tone: 'amber',
  },
  'annotation.dismissed': {
    labelKey: 'events.annotationDismissed.label',
    descriptionKey: 'events.annotationDismissed.description',
    tone: 'amber',
  },
  'annotation.classified': {
    labelKey: 'events.annotationClassified.label',
    descriptionKey: 'events.annotationClassified.description',
    tone: 'blue',
  },
  'placement.confirmed': {
    labelKey: 'events.placementConfirmed.label',
    descriptionKey: 'events.placementConfirmed.description',
    tone: 'green',
  },
  'placement.reattached': {
    labelKey: 'events.placementReattached.label',
    descriptionKey: 'events.placementReattached.description',
    tone: 'amber',
  },
  'annotation.auto_closed': {
    labelKey: 'events.annotationAutoClosed.label',
    descriptionKey: 'events.annotationAutoClosed.description',
    tone: 'amber',
  },
  'placement.repositioned': {
    labelKey: 'events.placementRepositioned.label',
    descriptionKey: 'events.placementRepositioned.description',
    tone: 'amber',
  },
  'workflow.transition': {
    labelKey: 'events.workflowTransition.label',
    descriptionKey: 'events.workflowTransition.description',
    tone: 'blue',
  },
  'document.due_date.changed': {
    labelKey: 'events.dueDateChanged.label',
    descriptionKey: 'events.dueDateChanged.description',
    tone: 'neutral',
  },
  'extraction.succeeded': {
    labelKey: 'events.extractionSucceeded.label',
    descriptionKey: 'events.extractionSucceeded.description',
    tone: 'green',
  },
  'extraction.failed': {
    labelKey: 'events.extractionFailed.label',
    descriptionKey: 'events.extractionFailed.description',
    tone: 'red',
  },
  'storage.orphan.deleted': {
    labelKey: 'events.storageOrphanDeleted.label',
    descriptionKey: 'events.storageOrphanDeleted.description',
    tone: 'amber',
  },
  'scheduler.job.run': {
    labelKey: 'events.schedulerJobRun.label',
    descriptionKey: 'events.schedulerJobRun.description',
    tone: 'amber',
  },
  'settings.updated': {
    labelKey: 'events.settingsUpdated.label',
    descriptionKey: 'events.settingsUpdated.description',
    tone: 'amber',
  },
  'scheduler.job.updated': {
    labelKey: 'events.schedulerJobUpdated.label',
    descriptionKey: 'events.schedulerJobUpdated.description',
    tone: 'neutral',
  },
  'review.archived': {
    labelKey: 'events.reviewArchived.label',
    descriptionKey: 'events.reviewArchived.description',
    tone: 'neutral',
  },
  'review.unarchived': {
    labelKey: 'events.reviewUnarchived.label',
    descriptionKey: 'events.reviewUnarchived.description',
    tone: 'blue',
  },
  'review.purged': {
    labelKey: 'events.reviewPurged.label',
    descriptionKey: 'events.reviewPurged.description',
    tone: 'red',
  },
};

/** The event types in a sensible display order — drives the filter and the legend. */
export const AUDIT_EVENT_TYPES = Object.keys(AUDIT_EVENT_META);

/** Metadata for an event type, with a safe fallback for any future/unknown type. */
export function auditEventMeta(eventType: string): AuditEventMeta {
  return (
    AUDIT_EVENT_META[eventType] ?? {
      labelKey: 'events.fallback.label',
      descriptionKey: 'events.fallback.description',
      tone: 'neutral',
    }
  );
}

const WORKFLOW_STATE_KEYS: Record<string, AuditKey> = {
  DRAFT: 'workflowStates.DRAFT',
  IN_REVIEW: 'workflowStates.IN_REVIEW',
  CHANGES_REQUESTED: 'workflowStates.CHANGES_REQUESTED',
  FINALIZED: 'workflowStates.FINALIZED',
  CANCELLED: 'workflowStates.CANCELLED',
};

/** A workflow state as shown to a human; unknown (enterprise) states pass through verbatim. */
export function humanizeWorkflowState(state: string): AuditPhrase {
  const key = WORKFLOW_STATE_KEYS[state];
  return key ? { key } : rawPhrase(state);
}

/**
 * Turns an enum-ish token (`CHANGES_REQUESTED`, `HIGH`) into `Changes requested` / `High`. A
 * technical fallback for open-vocabulary tokens, so its output is not translated.
 */
export function humanizeToken(token: string): string {
  const spaced = token.replace(/_/g, ' ').toLowerCase().trim();
  return spaced ? spaced.charAt(0).toUpperCase() + spaced.slice(1) : token;
}
