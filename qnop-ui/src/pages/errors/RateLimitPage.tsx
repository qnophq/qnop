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
import { ErrorState } from './ErrorState';
import { RateLimitIllustration } from './illustrations';

/** 429 - easy there; the rate limit (ADR-0027) asked for a breather (issue #611). */
export function RateLimitPage() {
  const navigate = useNavigate();
  return (
    <ErrorState
      code="429"
      title="Easy there, stamp champion"
      message="That was a lot of requests in a very short time. Take a breath - a moment from now everything works again."
      illustration={<RateLimitIllustration />}
      tone="alert"
      primaryAction={{ label: 'Try again', onClick: () => navigate(0) }}
      secondaryAction={{ label: 'Back to dashboard', to: '/' }}
    />
  );
}
