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

import { ToneBadge } from '../admin/ToneBadge';
import { WORKFLOW_TONES, workflowLabel } from './workflowMeta';

/**
 * The review's status badge. An archived review (issue #576) reads neutrally as
 * "Archived · <outcome>" — the terminal workflowState is preserved, so the badge
 * keeps showing whether it was finalized or cancelled underneath the archive.
 */
export function WorkflowBadge({
  state,
  archivedAt,
}: {
  state: string;
  archivedAt?: string | null;
}) {
  if (archivedAt) {
    return <ToneBadge tone="neutral" label={`Archived · ${workflowLabel(state)}`} />;
  }
  return <ToneBadge tone={WORKFLOW_TONES[state] ?? 'neutral'} label={workflowLabel(state)} />;
}
