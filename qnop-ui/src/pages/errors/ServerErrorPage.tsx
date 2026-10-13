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

import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { ErrorState } from './ErrorState';
import { ServerErrorIllustration } from './illustrations';

/** 500 - our shredder, our fault; self-deprecating, never blaming (issue #611). */
export function ServerErrorPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <ErrorState
      code="500"
      title={t('errors.serverError.title')}
      message={t('errors.serverError.message')}
      illustration={<ServerErrorIllustration />}
      tone="alert"
      primaryAction={{ label: t('actions.tryAgain'), onClick: () => navigate(0) }}
      secondaryAction={{ label: t('actions.backToDashboard'), to: '/' }}
    />
  );
}
