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

import type { ServerConfigFeatures } from '../../api/generated';

const EMAIL_TABS = [
  { path: 'server', label: 'Server', feature: 'smtpConfiguration' },
  { path: 'templates', label: 'Templates', feature: 'emailTemplates' },
] as const;

/**
 * The tabs this deployment offers (issues #678/#679). A withheld capability
 * refuses at its endpoints regardless; dropping the tab keeps the shell from
 * pointing at a page whose every action would be denied. While the config is
 * loading both stay — "not known yet" is not "not available".
 *
 * <p>Its own module because the layout beside it exports components, and a file
 * that mixes the two breaks fast refresh (the lint rule that caught it).
 */
export function offeredEmailTabs(features?: ServerConfigFeatures) {
  return EMAIL_TABS.filter((tab) => !features || features[tab.feature]);
}
