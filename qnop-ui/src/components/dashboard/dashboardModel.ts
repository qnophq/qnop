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
import type { DocumentSummary } from '../../api/generated';
import { isOpenWorkflowState } from '../reviews/workflowMeta';
import { roleOf } from '../reviews/list/reviewListModel';

const DAY_MS = 86_400_000;

/** A key in the `dashboard` catalog (issue #836) — the model names text, the view translates it. */
export type DashboardKey = ParseKeys<'dashboard'>;

/** The dashboard's shared urgency language (prototype: overdue / today / soon). */
export interface DueUrgency {
  level: 'overdue' | 'today' | 'soon' | 'later';
  /** Catalog key of the label; plural keys take {@link count}. */
  labelKey: DashboardKey;
  /** The day count for the overdue / days-left labels. */
  count?: number;
}

/**
 * How a due date reads on the dashboard: overdue in days, "Due today", or the
 * remaining time — `soon` within three days, `later` beyond.
 */
export function dueUrgency(dueAt: string, now: Date = new Date()): DueUrgency {
  // Calendar days, not 24h windows — "tomorrow" means the next date.
  const startOfDay = (date: Date) =>
    new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const diffDays = Math.round((startOfDay(new Date(dueAt)) - startOfDay(now)) / DAY_MS);
  if (diffDays < 0) {
    return { level: 'overdue', labelKey: 'due.overdue', count: -diffDays };
  }
  if (diffDays === 0) return { level: 'today', labelKey: 'due.today' };
  if (diffDays === 1) return { level: 'soon', labelKey: 'due.tomorrow' };
  return { level: diffDays <= 3 ? 'soon' : 'later', labelKey: 'due.daysLeft', count: diffDays };
}

/** By due-date urgency first (missing dates last), then most recently updated. */
function byUrgencyThenActivity(a: DocumentSummary, b: DocumentSummary): number {
  if (a.dueAt && b.dueAt && a.dueAt !== b.dueAt) return a.dueAt.localeCompare(b.dueAt);
  if (Boolean(a.dueAt) !== Boolean(b.dueAt)) return a.dueAt ? -1 : 1;
  return b.updatedAt.localeCompare(a.updatedAt);
}

/**
 * The reviewer hat's worklist: every still-running review I do NOT own — a
 * fresh assignment without a single annotation yet waits on the reviewer most
 * of all (issue #472).
 */
export function waitingOnYou(reviews: DocumentSummary[], userId: string | null) {
  return reviews
    .filter(
      (review) =>
        roleOf(review, userId) === 'reviewer' && isOpenWorkflowState(review.workflowState),
    )
    .sort(byUrgencyThenActivity);
}

/** The owner hat: my reviews, running ones first, each most recent first. */
export function myReviews(reviews: DocumentSummary[], userId: string | null) {
  return reviews
    .filter((review) => roleOf(review, userId) === 'owner')
    .sort((a, b) => {
      const openA = isOpenWorkflowState(a.workflowState);
      const openB = isOpenWorkflowState(b.workflowState);
      if (openA !== openB) return openA ? -1 : 1;
      return b.updatedAt.localeCompare(a.updatedAt);
    });
}

/** Every open review with a deadline, soonest first — the deadlines rail. */
export function deadlines(reviews: DocumentSummary[]) {
  return reviews
    .filter((review) => Boolean(review.dueAt) && isOpenWorkflowState(review.workflowState))
    .sort((a, b) => (a.dueAt as string).localeCompare(b.dueAt as string));
}

/** An owner cue: every concern settled, the review only awaits finalizing. */
export function readyToFinalize(review: DocumentSummary): boolean {
  return (
    isOpenWorkflowState(review.workflowState) &&
    review.annotationCount > 0 &&
    review.openAnnotationCount === 0
  );
}

/** What an activity-feed entry did — each surface phrases it from its own catalog. */
export type ActivityKind =
  | 'annotationCreated'
  | 'annotationResolved'
  | 'annotationReopened'
  | 'workflowTransition'
  | 'dueDateChanged'
  | 'other';

/** The activity feed's kind per audit event type (issue #454). */
export function activityKind(type: string): ActivityKind {
  switch (type) {
    case 'annotation.created':
      return 'annotationCreated';
    case 'annotation.resolved':
      return 'annotationResolved';
    case 'annotation.reopened':
      return 'annotationReopened';
    case 'workflow.transition':
      return 'workflowTransition';
    case 'document.due_date.changed':
      return 'dueDateChanged';
    default:
      return 'other';
  }
}

/** The prototype's time-of-day greeting, as its catalog key. */
export function greetingKey(hour: number, named: boolean): DashboardKey {
  const part = hour < 11 ? 'morning' : hour < 18 ? 'afternoon' : 'evening';
  return named ? `greeting.${part}.named` : `greeting.${part}.plain`;
}

/** The greeting's small companion — one emoji, matched to the hour. */
export function greetingEmoji(hour: number): string {
  if (hour < 11) return '☀️';
  if (hour < 18) return '👋';
  return '🌙';
}

/** The review page path — the slug when one exists, the id otherwise. */
export function reviewPath(review: { id: string; slug?: string | null }): string {
  return `/reviews/${review.slug ?? review.id}`;
}
