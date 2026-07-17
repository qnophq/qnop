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

import { resolveTimeZone, TIMEZONE_SETTING_KEY } from '../../utils/timezone';
import { useConfig } from './useConfig';
import { useUserSettingValue } from './useUserSettings';

/**
 * The active display timezone (issue #465, ADR-0041): the user's profile
 * `timezone` preference, falling back to the application default
 * (`general.defaultTimezone` on `/config`), then to UTC. Rendering flows through
 * {@link useFormatters}, so components never call this directly for formatting.
 */
export function useDisplayTimezone(): string {
  const { data: config } = useConfig();
  const userZone = useUserSettingValue(TIMEZONE_SETTING_KEY);
  return resolveTimeZone(userZone, config?.general.defaultTimezone);
}
