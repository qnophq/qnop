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

import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { workflowLabel, workflowLabelKey } from './workflowMeta';

/**
 * The label of a workflow state in the active language (issue #836): a known state comes from
 * the `reviews` catalog, an unknown (enterprise) one falls back to its humanized name.
 */
export function useWorkflowLabel(): (state: string) => string {
  const { t } = useTranslation('reviews');
  return useCallback(
    (state: string) => {
      const key = workflowLabelKey(state);
      return key ? t(key) : workflowLabel(state);
    },
    [t],
  );
}
