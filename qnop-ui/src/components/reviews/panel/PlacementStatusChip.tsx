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
import { useTranslation } from 'react-i18next';
import { PlacementStatus } from '../../../api/generated';
import type { BadgeTone } from '../../admin/ToneBadge';
import { ToneBadge } from '../../admin/ToneBadge';

/**
 * The placement-status cue (ADR-0009): how the annotation's anchor resolved
 * on the version being viewed. PLACED renders nothing — it is the expected
 * state and needs no badge. Label and hint come from the `workspace` catalog
 * (`annotation.placement.<STATUS>`).
 */
const TONES: Partial<Record<PlacementStatus, BadgeTone>> = {
  [PlacementStatus.Pending]: 'neutral',
  [PlacementStatus.Moved]: 'amber',
  [PlacementStatus.Orphaned]: 'red',
  [PlacementStatus.Failed]: 'red',
};

export function PlacementStatusChip({ status }: { status?: PlacementStatus }) {
  const { t } = useTranslation('workspace');
  const tone = status ? TONES[status] : undefined;
  if (!status || !tone) return null;
  const key = status as Exclude<PlacementStatus, 'PLACED'>;
  return (
    <Tooltip title={t(`annotation.placement.${key}.hint`)}>
      <span>
        <ToneBadge
          tone={tone}
          label={t(`annotation.placement.${key}.label`)}
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
