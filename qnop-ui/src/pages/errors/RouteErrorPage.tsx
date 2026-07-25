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

import { isRouteErrorResponse, useRouteError } from 'react-router';
import { NotFoundPage } from './NotFoundPage';
import { ServerErrorPage } from './ServerErrorPage';

/**
 * The router-level catch (issue #611): a throw during navigation lands on the
 * branded shell instead of React Router's default screen. A 404 response
 * renders the not-found page; everything else is, honestly, a 500 of ours.
 * Pane-level render errors stay with ErrorBoundary/BoundaryFallback (#331).
 */
export function RouteErrorPage() {
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) {
    return <NotFoundPage />;
  }
  return <ServerErrorPage />;
}
