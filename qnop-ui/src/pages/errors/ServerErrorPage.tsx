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
import { ServerErrorIllustration } from './illustrations';

/** 500 - our shredder, our fault; self-deprecating, never blaming (issue #611). */
export function ServerErrorPage() {
  const navigate = useNavigate();
  return (
    <ErrorState
      code="500"
      title="Our shredder grabbed the wrong document"
      message="Something broke on our side - not your doing. Trying again usually works; if it keeps happening, your admin will want to know."
      illustration={<ServerErrorIllustration />}
      tone="alert"
      primaryAction={{ label: 'Try again', onClick: () => navigate(0) }}
      secondaryAction={{ label: 'Back to dashboard', to: '/' }}
    />
  );
}
