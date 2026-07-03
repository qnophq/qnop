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

import type { BadgeTone } from '../admin/ToneBadge';

/**
 * Presentation of the review workflow states (ADR-0011). The state set is
 * deliberately open — an enterprise state machine may add states — so unknown
 * values render neutrally and count as "open" (not finished).
 */
export const WORKFLOW_TONES: Record<string, BadgeTone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'blue',
  CHANGES_REQUESTED: 'amber',
  FINALIZED: 'green',
  CANCELLED: 'red',
};

const CLOSED_STATES = new Set(['FINALIZED', 'CANCELLED']);

/** Human label for a workflow state: `IN_REVIEW` → `In review`. */
export function workflowLabel(state: string): string {
  return state
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/^./, (c) => c.toUpperCase());
}

/** Whether the review is still running (unknown enterprise states count as open). */
export function isOpenWorkflowState(state: string): boolean {
  return !CLOSED_STATES.has(state);
}
