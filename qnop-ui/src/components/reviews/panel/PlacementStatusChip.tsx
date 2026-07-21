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

import CircularProgress from '@mui/material/CircularProgress';
import Tooltip from '@mui/material/Tooltip';
import { PlacementStatus } from '../../../api/generated';
import type { BadgeTone } from '../../admin/ToneBadge';
import { ToneBadge } from '../../admin/ToneBadge';

/**
 * The placement-status cue (ADR-0009): how the annotation's anchor resolved
 * on the version being viewed. PLACED renders nothing — it is the expected
 * state and needs no badge.
 */
const CUES: Partial<Record<PlacementStatus, { tone: BadgeTone; label: string; hint: string }>> = {
  [PlacementStatus.Pending]: {
    tone: 'neutral',
    label: 'Re-anchoring…',
    hint: 'The new version is still being matched against this annotation.',
  },
  [PlacementStatus.Moved]: {
    tone: 'amber',
    label: 'Moved',
    hint: 'The anchored text changed with the new version — please verify the highlight.',
  },
  [PlacementStatus.Orphaned]: {
    tone: 'red',
    label: 'Orphaned',
    hint: 'No confident match on this version — the annotation needs manual handling.',
  },
  [PlacementStatus.Failed]: {
    tone: 'red',
    label: 'Failed',
    hint: 'Re-anchoring failed for this annotation.',
  },
};

export function PlacementStatusChip({ status }: { status?: PlacementStatus }) {
  if (!status) return null;
  const cue = CUES[status];
  if (!cue) return null;
  return (
    <Tooltip title={cue.hint}>
      <span>
        <ToneBadge
          tone={cue.tone}
          label={cue.label}
          icon={
            // Re-anchoring is actively running (issue #552) — a spinner in the
            // badge's own foreground colour signals work-in-progress. The
            // label carries the meaning, so the glyph is decorative.
            status === PlacementStatus.Pending ? (
              <CircularProgress
                size={10}
                thickness={5}
                color="inherit"
                aria-hidden
                data-testid="placement-pending-spinner"
              />
            ) : undefined
          }
        />
      </span>
    </Tooltip>
  );
}
