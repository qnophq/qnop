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

import type { LucideIcon } from 'lucide-react';
import { Flag, MessageCircleQuestion, Pencil, Plus, TriangleAlert } from 'lucide-react';
import type { Theme } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationPriority, AnnotationStatus, AnnotationType } from '../../../api/generated';

/**
 * The board's columns (issue #393, prototype reviewhub): *In discussion* is
 * DERIVED — an OPEN annotation whose thread grew beyond its mandatory first
 * comment (#301) is being discussed, exactly like the prototype's auto
 * promotion on reply. The `done` column carries every resolved annotation
 * (labelled *Resolved*, issue #405).
 */
export type TaskColumn = 'open' | 'discussion' | 'done';

export const TASK_COLUMNS: TaskColumn[] = ['open', 'discussion', 'done'];

export function columnOf(annotation: AnnotationView): TaskColumn {
  if (annotation.status !== AnnotationStatus.Open) return 'done';
  return annotation.commentCount > 1 ? 'discussion' : 'open';
}

/** The sub-toolbar's status filter; `all` shows every column. */
export type TaskFilter = 'all' | TaskColumn;

export function parseTaskFilter(raw: string | null): TaskFilter {
  return raw === 'open' || raw === 'discussion' || raw === 'done' ? raw : 'all';
}

/** The card's title: the thread's opening comment; quote/fallback otherwise. */
export function taskTitle(annotation: AnnotationView): string {
  return annotation.firstComment?.trim() || annotation.anchor?.textQuote?.quote || 'Annotation';
}

/**
 * Stable per-review shorthand keys (T-1, T-2, …) in creation order — the
 * tracker-style card id (YouTrack's "CSS-17", Zoho's "MBA-I78"). Purely a
 * display affordance; the UUID stays the identity.
 */
export function taskKeys(annotations: AnnotationView[]): Map<string, string> {
  const ordered = [...annotations].sort(
    (a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id),
  );
  return new Map(ordered.map((annotation, index) => [annotation.id, `T-${index + 1}`]));
}

/**
 * The free-text search of the sub-toolbar — title, quoted passage and the
 * resolved author name, mirroring the prototype's task search.
 */
export function matchesQuery(
  annotation: AnnotationView,
  authorName: string,
  query: string,
): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return (
    taskTitle(annotation).toLowerCase().includes(q) ||
    (annotation.anchor?.textQuote?.quote ?? '').toLowerCase().includes(q) ||
    authorName.toLowerCase().includes(q)
  );
}

/** Prototype's violet for proposals — no semantic palette slot fits it. */
const PROPOSAL_VIOLET = '#6B54E5';
/** Prototype's dark amber for risks — readable on both surfaces. */
const RISK_AMBER = '#B77F00';

/**
 * Type → label/icon/colour, the tasks view's visual language (prototype
 * `typeMeta`). Colours lean on the semantic palette where one fits.
 */
export const TYPE_CUES: Record<
  AnnotationType,
  { label: string; icon: LucideIcon; color: (theme: Theme) => string }
> = {
  [AnnotationType.Change]: {
    label: 'Change',
    icon: Pencil,
    color: (theme) => theme.qnop.brand.blue,
  },
  [AnnotationType.Proposal]: {
    label: 'Proposal',
    icon: Plus,
    color: () => PROPOSAL_VIOLET,
  },
  [AnnotationType.Conflict]: {
    label: 'Conflict',
    icon: TriangleAlert,
    color: (theme) => theme.palette.error.main,
  },
  [AnnotationType.Question]: {
    label: 'Question',
    icon: MessageCircleQuestion,
    color: (theme) => theme.palette.text.secondary,
  },
  [AnnotationType.Risk]: {
    label: 'Risk',
    icon: Flag,
    color: () => RISK_AMBER,
  },
};

/** Priority → label/colour for the card's leading dot (prototype `prioMeta`). */
export const PRIORITY_CUES: Record<
  AnnotationPriority,
  { label: string; color: (theme: Theme) => string }
> = {
  [AnnotationPriority.High]: { label: 'High', color: (theme) => theme.palette.error.main },
  [AnnotationPriority.Medium]: { label: 'Medium', color: (theme) => theme.palette.warning.main },
  [AnnotationPriority.Low]: { label: 'Low', color: (theme) => theme.palette.text.disabled },
};
