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
import { CircleCheck, CircleDot } from 'lucide-react';
import type { Theme } from '@mui/material/styles';
import { AnnotationStatus } from '../../../api/generated';
import type { BadgeTone } from '../../admin/ToneBadge';

/**
 * Status → badge tone/label/icon/rail colour — shared by the panel's list
 * items and the focus overlay card (#291), so both surfaces read identically.
 */
export const STATUS_CUES: Record<
  AnnotationStatus,
  { tone: BadgeTone; label: string; icon: LucideIcon; color: (theme: Theme) => string }
> = {
  [AnnotationStatus.Open]: {
    tone: 'blue',
    label: 'Open',
    icon: CircleDot,
    color: (theme) => theme.qnop.brand.blue,
  },
  [AnnotationStatus.Resolved]: {
    tone: 'green',
    label: 'Resolved',
    icon: CircleCheck,
    color: (theme) => theme.palette.success.main,
  },
};
