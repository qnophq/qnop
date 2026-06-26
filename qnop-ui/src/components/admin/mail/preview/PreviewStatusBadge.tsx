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

import { ToneBadge, type BadgeTone } from '../../ToneBadge';
import type { PreviewStatus } from '../../../../api/hooks/useMailTemplatePreview';

const TONE_BY_STATUS: Record<PreviewStatus, { tone: BadgeTone; label: string }> = {
  idle: { tone: 'neutral', label: 'Idle' },
  stale: { tone: 'amber', label: 'Pending' },
  syncing: { tone: 'blue', label: 'Rendering' },
  live: { tone: 'green', label: 'Live' },
  error: { tone: 'red', label: 'Error' },
};

/** A coloured pill mirroring the live-preview sync lifecycle (issue #145). */
export function PreviewStatusBadge({ status }: { status: PreviewStatus }) {
  const { tone, label } = TONE_BY_STATUS[status];
  return <ToneBadge tone={tone} label={label} />;
}
