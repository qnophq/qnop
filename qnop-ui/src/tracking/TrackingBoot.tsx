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

import { useEffect, useState } from 'react';
import { Outlet, useLocation, useParams } from 'react-router';
import type { ServerConfigTracking } from '../api/generated';
import { useConfig } from '../api/hooks/useConfig';
import { useUserSettingValue } from '../api/hooks/useUserSettings';
import { useAuthStore } from '../stores/authStore';
import { ConsentBar } from './ConsentBar';
import { readConsent, type ConsentDecision } from './consent';
import { ADAPTERS, SCRIPT_URL } from './providers';
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
 * <p>Nothing is fetched, injected or sent until that decision says so. When measurement is off, the
 * script tag does not exist in the page at all.
 */
export function TrackingBoot() {
  const { data: config } = useConfig();
  const tracking = config?.tracking;
  const role = useAuthStore((s) => s.role);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const optOut = useUserSettingValue(OPT_OUT_KEY) === 'true';
  const [consent, setConsent] = useState<ConsentDecision>(() => readConsent());
  const [loaded, setLoaded] = useState(false);
  const location = useLocation();
  const params = useParams();

  const gate = resolveGate({ tracking, consent, optOut, isAuthenticated, role });

  useEffect(() => {
    if (gate !== 'measure' || !tracking || loaded) {
      return;
    }
    loadTracker(tracking).then(setLoaded);
  }, [gate, tracking, loaded]);

  useEffect(() => {
    if (!loaded || !tracking) {
      return;
    }
    // The pattern, never the path: /reviews/:documentId, not the id of a
    // customer's contract.
    ADAPTERS[tracking.provider]?.pageview(anonymizePath(location.pathname, params));
    // `params` is a fresh object every render; the pathname is what changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded, tracking, location.pathname]);

  return (
    <>
      <Outlet />
      {gate === 'ask' && <ConsentBar onDecide={setConsent} />}
    </>
  );
}

/** Injects the proxied vendor script exactly once; resolves false when it will not load. */
function loadTracker(tracking: ServerConfigTracking): Promise<boolean> {
  const adapter = ADAPTERS[tracking.provider];
  if (!adapter || document.querySelector(`script[src="${SCRIPT_URL}"]`)) {
    return Promise.resolve(Boolean(adapter));
  }
  adapter.beforeLoad?.(tracking.siteId);
  return new Promise((resolve) => {
    const script = document.createElement('script');
    script.src = SCRIPT_URL;
    script.defer = true;
    for (const [name, value] of Object.entries(adapter.attributes(tracking.siteId))) {
      script.setAttribute(name, value);
    }
    script.onload = () => {
      adapter.afterLoad?.(tracking.siteId);
      setActiveTracker(adapter);
      resolve(true);
    };
    // A backend that is down, or a deployment that mistyped its host: measurement
    // is the one feature allowed to fail in total silence.
    script.onerror = () => resolve(false);
    document.head.appendChild(script);
  });
}
