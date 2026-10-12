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

import { useTranslation } from 'react-i18next';
import { ErrorState } from './ErrorState';
import { MaintenanceIllustration } from './illustrations';

/**
 * A capability this deployment does not offer (issue #682).
 *
 * <p>Deliberately not the 403 page. That one says the account lacks access,
 * which invites the reader to ask an administrator for a role — and no role
 * changes this answer. The capability was withheld by whoever operates the
 * deployment, so the wording names the deployment and nothing else. It is
 * still a 403: the request was understood and refused, and it will be refused
 * again tomorrow.
 */
export function FeatureUnavailablePage() {
  const { t } = useTranslation();
  return (
    <ErrorState
      code="403"
      title={t('errors.featureUnavailable.title')}
      message={t('errors.featureUnavailable.message')}
      illustration={<MaintenanceIllustration />}
      primaryAction={{ label: t('actions.backToDashboard'), to: '/' }}
    />
  );
}
