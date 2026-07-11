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

import type { DocumentSummary } from '../../api/generated';
import { isOpenWorkflowState } from '../reviews/workflowMeta';
import { roleOf } from '../reviews/list/reviewListModel';

const DAY_MS = 86_400_000;

/** The dashboard's shared urgency language (prototype: overdue / today / soon). */
export interface DueUrgency {
  level: 'overdue' | 'today' | 'soon' | 'later';
  label: string;
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
    const days = -diffDays;
    return { level: 'overdue', label: `Overdue by ${days} ${days === 1 ? 'day' : 'days'}` };
  }
  if (diffDays === 0) return { level: 'today', label: 'Due today' };
  if (diffDays === 1) return { level: 'soon', label: 'Due tomorrow' };
  return { level: diffDays <= 3 ? 'soon' : 'later', label: `${diffDays} days left` };
}

/** By due-date urgency first (missing dates last), then most recently updated. */
function byUrgencyThenActivity(a: DocumentSummary, b: DocumentSummary): number {
  if (a.dueAt && b.dueAt && a.dueAt !== b.dueAt) return a.dueAt.localeCompare(b.dueAt);
  if (Boolean(a.dueAt) !== Boolean(b.dueAt)) return a.dueAt ? -1 : 1;
  return b.updatedAt.localeCompare(a.updatedAt);
}

/**
 * The reviewer hat's worklist: reviews I do NOT own, still running, with open
 * annotations — what the round is waiting on.
 */
export function waitingOnYou(reviews: DocumentSummary[], userId: string | null) {
  return reviews
    .filter(
      (review) =>
        roleOf(review, userId) === 'reviewer' &&
        isOpenWorkflowState(review.workflowState) &&
        review.openAnnotationCount > 0,
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

/** The activity feed's verb phrase per audit event type (issue #454). */
export function activityPhrase(type: string): string {
  switch (type) {
    case 'annotation.created':
      return 'opened an annotation in';
    case 'annotation.resolved':
      return 'resolved an annotation in';
    case 'annotation.reopened':
      return 'reopened an annotation in';
    case 'workflow.transition':
      return 'changed the state of';
    case 'document.due_date.changed':
      return 'changed the due date of';
    default:
      return 'updated';
  }
}

/** The prototype's time-of-day greeting. */
export function greeting(hour: number): string {
  if (hour < 11) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

/** The review page path — the slug when one exists, the id otherwise. */
export function reviewPath(review: { id: string; slug?: string | null }): string {
  return `/reviews/${review.slug ?? review.id}`;
}
