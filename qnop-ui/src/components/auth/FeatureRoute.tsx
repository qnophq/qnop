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

import type { ReactNode } from 'react';
import { Navigate } from 'react-router';
import { useConfig } from '../../api/hooks/useConfig';
import type { ServerConfigFeatures } from '../../api/generated';

interface FeatureRouteProps {
  /** The capability this destination needs; the page exists only where it does. */
  feature: keyof ServerConfigFeatures;
  children: ReactNode;
}

/**
 * Gate for routes that administer a capability a deployment may withhold
 * (issue #682).
 *
 * <p>The sidebar already drops these pages, but a URL is something a person can
 * type or bookmark, and a screen whose every action the server refuses is worse
 * than no screen — the same reasoning that removed the export button in #674.
 *
 * <p>It navigates to the full-page error rather than rendering one inside the
 * shell. A refused destination is not a state of the application — it is a wall
 * — and drawing it under the sidebar and, for the mail pages, under the Email
 * header and its tab strip made it look like a panel that failed to load. The
 * branded error routes live outside the shell for the same reason (issue #611);
 * this one joins them. The cost is the address bar, which now says
 * /feature-unavailable rather than the page that was asked for.
 *
 * <p>Nothing renders until the config has arrived. "Not known yet" is not "not
 * available", and flashing an error onto deployments that do have the
 * capability would be worse than a moment of blank.
 */
export function FeatureRoute({ feature, children }: FeatureRouteProps) {
  const { data, isError } = useConfig();

  if (!data) {
    // A config that failed to load is not evidence of anything: show the page
    // and let the endpoints answer, exactly as the sidebar keeps items it
    // cannot rule out. Still waiting, on the other hand, renders nothing.
    return isError ? children : null;
  }
  return data.features?.[feature] === false ? (
    <Navigate to="/feature-unavailable" replace />
  ) : (
    children
  );
}
