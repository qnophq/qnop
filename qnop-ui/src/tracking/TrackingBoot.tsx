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

import { useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation, useParams } from 'react-router';
import { useConfig } from '../api/hooks/useConfig';
import { useUserSettingValue } from '../api/hooks/useUserSettings';
import { useAuthStore } from '../stores/authStore';
import { ConsentBar } from './ConsentBar';
import { readConsent, type ConsentDecision } from './consent';
import { createTracker } from './providers';
import { setActiveTracker } from './trackEvent';
import { OPT_OUT_KEY, resolveGate } from './trackingGate';
import { anonymizePath } from './trackingPath';

/**
 * Mounts usage measurement, or does not (issue #666).
 *
 * <p>Sits above the whole route table as a pathless layout: the sign-in screen is measured like
 * everything else, and every navigation — not only those inside the shell — reports a page view.
 * The decision itself lives in {@link resolveGate}; what happens here is only its consequence.
 *
 * <p>Nothing is sent until that decision says so, and no analytics vendor script is ever loaded
 * (issue #910): measurements are built here, in the backend's collect format, and posted to this
 * origin's proxy. Should the decision change — an opt-out, a withdrawn consent — measuring stops at
 * once rather than at the next reload.
 */
export function TrackingBoot() {
  const { data: config } = useConfig();
  const tracking = config?.tracking;
  const role = useAuthStore((s) => s.role);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const optOut = useUserSettingValue(OPT_OUT_KEY) === 'true';
  const [consent, setConsent] = useState<ConsentDecision>(() => readConsent());
  const location = useLocation();
  const params = useParams();

  const gate = resolveGate({ tracking, consent, optOut, isAuthenticated, role });
  const provider = tracking?.provider;
  const siteId = tracking?.siteId;

  const tracker = useMemo(
    () => (gate === 'measure' && provider && siteId ? createTracker(provider, siteId) : null),
    [gate, provider, siteId],
  );

  useEffect(() => {
    setActiveTracker(tracker);
    return () => setActiveTracker(null);
  }, [tracker]);

  useEffect(() => {
    // The pattern, never the path: /reviews/:documentId, not the id of a
    // customer's contract.
    tracker?.pageview(anonymizePath(location.pathname, params));
    // `params` is a fresh object every render; the pathname is what changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracker, location.pathname]);

  return (
    <>
      <Outlet />
      {gate === 'ask' && <ConsentBar onDecide={setConsent} />}
    </>
  );
}
