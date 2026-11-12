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

import type { TrackedEvent } from './trackEvent';

/** Where the proxied measurements go on this origin. */
export const COLLECT_BASE = '/t/c';

/** One measurement, ready to send to {@link COLLECT_BASE}: the path carries any query string. */
export interface Beacon {
  method: 'GET' | 'POST';
  path: string;
  body?: string;
  contentType?: string;
}

/** What every measurement may say about the visit — and nothing more. */
export interface BeaconContext {
  siteId: string;
  /** Public knowledge: the backends that want a full URL get this origin plus a route pattern. */
  origin: string;
  hostname: string;
  language: string;
  screenWidth: number;
  screenHeight: number;
  /** A random id per page load, for the one backend that insists on one (PostHog). */
  visitId: string;
}

/**
 * Builds measurements in a backend's own collect format (issue #910).
 *
 * qnop does not load any analytics vendor's script. A script served from this origin runs with the
 * application's full privileges — it could read the signed-in user's access token and call the API
 * as them — so a compromised or misconfigured analytics host would have been code execution in
 * every consenting reviewer's session. Each backend's collect endpoint is a small, documented
 * HTTP API, and building the request here is less code than configuring the vendor script was.
 *
 * Every `url` passed in is already a route pattern (`/reviews/:documentId`), never a real path;
 * no title and no referrer is ever sent. Pure functions, so the exact shape is asserted in tests.
 */
export interface TrackerAdapter {
  pageview(ctx: BeaconContext, url: string): Beacon;
  event(ctx: BeaconContext, name: TrackedEvent, url: string): Beacon;
}

const JSON_TYPE = 'application/json';
/** What the vendors' own scripts send JSON as, so the backend sees nothing unusual. */
const TEXT_TYPE = 'text/plain;charset=UTF-8';

function absolute(ctx: BeaconContext, url: string): string {
  return `${ctx.origin}${url}`;
}

function query(params: Record<string, string>): string {
  return new URLSearchParams(params).toString();
}

function post(path: string, body: unknown, contentType: string): Beacon {
  return { method: 'POST', path, body: JSON.stringify(body), contentType };
}

/** Matomo's HTTP Tracking API: a GET to `matomo.php`; no cookies, no visitor id, no title. */
const matomo: TrackerAdapter = {
  pageview: (ctx, url) => ({ method: 'GET', path: `/matomo.php?${matomoQuery(ctx, url)}` }),
  event: (ctx, name, url) => ({
    method: 'GET',
    path: `/matomo.php?${matomoQuery(ctx, url, { e_c: 'qnop', e_a: name })}`,
  }),
};

function matomoQuery(ctx: BeaconContext, url: string, extra: Record<string, string> = {}): string {
  return query({
    idsite: ctx.siteId,
    rec: '1',
    apiv: '1',
    // 204 instead of a tracking pixel.
    send_image: '0',
    url: absolute(ctx, url),
    res: `${ctx.screenWidth}x${ctx.screenHeight}`,
    ...extra,
    rand: String(Date.now()),
  });
}

/** Plausible's Events API, in the body shape its own script sends. */
const plausible: TrackerAdapter = {
  pageview: (ctx, url) => plausibleEvent(ctx, 'pageview', url),
  event: (ctx, name, url) => plausibleEvent(ctx, name, url),
};

function plausibleEvent(ctx: BeaconContext, name: string, url: string): Beacon {
  return post(
    '/api/event',
    { name, url: absolute(ctx, url), domain: ctx.siteId, referrer: null },
    TEXT_TYPE,
  );
}

/** Umami's collect endpoint: a page view is an event without a name. */
const umami: TrackerAdapter = {
  pageview: (ctx, url) => umamiEvent(ctx, url),
  event: (ctx, name, url) => umamiEvent(ctx, url, name),
};

function umamiEvent(ctx: BeaconContext, url: string, name?: string): Beacon {
  return post(
    '/api/send',
    {
      type: 'event',
      payload: {
        website: ctx.siteId,
        hostname: ctx.hostname,
        language: ctx.language,
        screen: `${ctx.screenWidth}x${ctx.screenHeight}`,
        url,
        referrer: '',
        ...(name ? { name } : {}),
      },
    },
    JSON_TYPE,
  );
}

/** PostHog's capture endpoint; anonymous events, no person profile. */
const posthog: TrackerAdapter = {
  pageview: (ctx, url) => posthogEvent(ctx, '$pageview', url),
  event: (ctx, name, url) => posthogEvent(ctx, name, url),
};

function posthogEvent(ctx: BeaconContext, event: string, url: string): Beacon {
  return post(
    '/i/v0/e/',
    {
      api_key: ctx.siteId,
      event,
      distinct_id: ctx.visitId,
      properties: {
        $current_url: absolute(ctx, url),
        $process_person_profile: false,
        $lib: 'qnop',
      },
    },
    JSON_TYPE,
  );
}

/**
 * Pirsch, in the format its `pa.js` uses: page views as a GET to `/hit`, events as a POST to
 * `/event`. Unlike the vendor script — which could only report the real URL — this sends the route
 * pattern, so the server-side rewrite is now a second line rather than the only one.
 */
const pirsch: TrackerAdapter = {
  pageview: (ctx, url) => ({
    method: 'GET',
    path: `/hit?${query({
      nc: String(Date.now()),
      code: ctx.siteId,
      url: absolute(ctx, url),
      t: '',
      ref: '',
      w: String(ctx.screenWidth),
      h: String(ctx.screenHeight),
    })}`,
  }),
  event: (ctx, name, url) =>
    post(
      '/event',
      {
        identification_code: ctx.siteId,
        url: absolute(ctx, url),
        title: '',
        referrer: '',
        screen_width: ctx.screenWidth,
        screen_height: ctx.screenHeight,
        tags: {},
        event_name: name,
        event_duration: 0,
        event_meta: {},
        non_interactive: false,
      },
      TEXT_TYPE,
    ),
};

export const ADAPTERS: Readonly<Record<string, TrackerAdapter>> = {
  matomo,
  plausible,
  umami,
  posthog,
  pirsch,
};

/** A tracker bound to one backend and one page load. */
export interface ActiveTracker {
  pageview(url: string): void;
  event(name: TrackedEvent): void;
}

/** Sends one measurement to this origin's proxy; failures are dropped without a trace. */
export function sendBeacon(beacon: Beacon): void {
  void fetch(`${COLLECT_BASE}${beacon.path}`, {
    method: beacon.method,
    headers: beacon.contentType ? { 'Content-Type': beacon.contentType } : undefined,
    body: beacon.body,
    keepalive: true,
    // The proxy is unauthenticated; nothing about the session travels with a measurement —
    // not a cookie, and not the real address of the page in a Referer header.
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    cache: 'no-store',
  }).catch(() => {
    // A measurement is the one thing allowed to fail in total silence.
  });
}

/**
 * Binds an adapter to this page load; null for a provider this client does not know.
 *
 * Events report the route pattern of the most recent page view, so an event never needs the real
 * location either.
 */
export function createTracker(
  provider: string,
  siteId: string,
  send: (beacon: Beacon) => void = sendBeacon,
): ActiveTracker | null {
  const adapter = Object.prototype.hasOwnProperty.call(ADAPTERS, provider)
    ? ADAPTERS[provider]
    : undefined;
  if (!adapter) {
    return null;
  }
  const ctx = browserContext(siteId);
  let currentUrl = '/';
  return {
    pageview(url) {
      currentUrl = url;
      send(adapter.pageview(ctx, url));
    },
    event(name) {
      send(adapter.event(ctx, name, currentUrl));
    },
  };
}

function browserContext(siteId: string): BeaconContext {
  return {
    siteId,
    origin: window.location.origin,
    hostname: window.location.hostname,
    language: navigator.language ?? '',
    screenWidth: window.screen?.width ?? 0,
    screenHeight: window.screen?.height ?? 0,
    visitId: randomId(),
  };
}

function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
