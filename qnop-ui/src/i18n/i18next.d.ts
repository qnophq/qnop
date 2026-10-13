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

import 'i18next';
import type enAuth from './en/auth.json';
import type enCommon from './en/common.json';
import type enNotifications from './en/notifications.json';
import type enProfile from './en/profile.json';
import type enShell from './en/shell.json';

/**
 * Types every `t()` key against the English catalog (ADR-0063): a misspelt or removed key fails
 * `tsc`, and English is the source the other catalogs are checked against for parity.
 */
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: {
      common: typeof enCommon;
      auth: typeof enAuth;
      shell: typeof enShell;
      profile: typeof enProfile;
      notifications: typeof enNotifications;
    };
    returnNull: false;
  }
}
