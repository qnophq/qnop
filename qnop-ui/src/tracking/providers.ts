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

/** Where the proxied script and the proxied measurements live on this origin. */
export const SCRIPT_URL = '/t/s.js';
export const COLLECT_BASE = '/t/c';

type Paq = unknown[][];

interface PlausibleFn {
  (event: string, options?: { u?: string; callback?: () => void }): void;
}

interface UmamiApi {
  track: (payload: unknown, data?: unknown) => void;
}

interface PosthogApi {
  init: (key: string, options: Record<string, unknown>) => void;
  capture: (event: string, properties?: Record<string, unknown>) => void;
}

declare global {
  interface Window {
    _paq?: Paq;
    plausible?: PlausibleFn;
    umami?: UmamiApi;
    posthog?: PosthogApi;
  }
}

/**
 * What each backend needs, reduced to three questions: what goes on the script tag, what to do once
 * it has loaded, and how to report a page view or an event.
 *
 * Every adapter has the same job beyond wiring: **stop the script from measuring on its own**.
 * Left alone, each of these reports `window.location` on load — which in qnop is a URL with a
 * document id in it. So auto-tracking is switched off wherever the vendor allows it, and qnop sends
 * route patterns instead.
 */
export interface TrackerAdapter {
  /** Attributes for the injected `<script>`; most backends are configured entirely this way. */
  attributes(siteId: string): Record<string, string>;
  /** Runs before the script is inserted (Matomo's queue has to exist first). */
  beforeLoad?(siteId: string): void;
  /** Runs once the script has loaded (PostHog is configured through its API). */
  afterLoad?(siteId: string): void;
  pageview(url: string): void;
  event(name: string): void;
}

const matomo: TrackerAdapter = {
  attributes: () => ({}),
  beforeLoad(siteId) {
    const paq: Paq = (window._paq = window._paq ?? []);
    paq.push(['setTrackerUrl', `${COLLECT_BASE}/matomo.php`]);
    paq.push(['setSiteId', siteId]);
    // Matomo tracks nothing until asked, which is exactly what qnop wants.
    paq.push(['disableCookies']);
  },
  pageview(url) {
    window._paq?.push(['setCustomUrl', url]);
    // Matomo would otherwise send document.title as the action name.
    window._paq?.push(['setDocumentTitle', url]);
    window._paq?.push(['trackPageView']);
  },
  event(name) {
    window._paq?.push(['trackEvent', 'qnop', name]);
  },
};

const plausible: TrackerAdapter = {
  attributes: (siteId) => ({ 'data-domain': siteId, 'data-api': `${COLLECT_BASE}/api/event` }),
  pageview(url) {
    window.plausible?.('pageview', { u: absolute(url) });
  },
  event(name) {
    window.plausible?.(name, { u: absolute(window.location.pathname) });
  },
};

const umami: TrackerAdapter = {
  attributes: (siteId) => ({
    'data-website-id': siteId,
    'data-host-url': COLLECT_BASE,
    // Without this it reports the real URL on load and on every history change.
    'data-auto-track': 'false',
  }),
  pageview(url) {
    // `title` is document.title, which qnop must not export (see the server's
    // sanitiser); it is blanked here so it never leaves the tab either.
    window.umami?.track((props: unknown) => ({ ...(props as object), url, title: '' }));
  },
  event(name) {
    window.umami?.track(name);
  },
};

const posthog: TrackerAdapter = {
  attributes: () => ({}),
  afterLoad(siteId) {
    window.posthog?.init(siteId, {
      api_host: COLLECT_BASE,
      // Everything that would collect on its own, off. Autocapture records the
      // text of what was clicked, and in qnop that text is a document title;
      // session recording would copy the document itself.
      autocapture: false,
      capture_pageview: false,
      capture_pageleave: false,
      disable_session_recording: true,
      disable_surveys: true,
      // Plain JSON, so the proxy can read — and sanitise — what travels.
      disable_compression: true,
      persistence: 'memory',
      // No remote config, no feature flags, no lazily-loaded modules. qnop uses
      // PostHog to count page views and nothing else, and each of those would
      // fetch a path (/array/<token>/config.js, /flags/, /static/<module>.js)
      // that the proxy's allowlist does not carry — by design, since an
      // allowlist that grew to cover them would stop being a boundary.
      advanced_disable_decide: true,
      advanced_disable_flags: true,
      disable_external_dependency_loading: true,
    });
  },
  pageview(url) {
    window.posthog?.capture('$pageview', { $current_url: absolute(url), $title: '' });
  },
  event(name) {
    window.posthog?.capture(name);
  },
};

const pirsch: TrackerAdapter = {
  attributes: (siteId) => ({
    id: 'pianjs',
    'data-code': siteId,
    // The names pa.js defaults to; the proxy forwards them under the same names.
    'data-hit-endpoint': `${COLLECT_BASE}/hit`,
    'data-event-endpoint': `${COLLECT_BASE}/event`,
    'data-session-endpoint': `${COLLECT_BASE}/session`,
    // Pirsch has no documented way to send a page view with a URL of our
    // choosing, so its automatic one stays on and the server rewrites the id
    // out of it (TrackedUrlSanitizer). Query strings never leave at all.
    'data-disable-query': 'true',
    'data-disable-referrer': 'true',
  }),
  pageview() {
    // Deliberately empty: see above.
  },
  event(name) {
    const fn = (window as unknown as { pirsch?: (name: string) => void }).pirsch;
    fn?.(name);
  },
};

export const ADAPTERS: Record<string, TrackerAdapter> = {
  matomo,
  plausible,
  umami,
  posthog,
  pirsch,
};

/** Backends that want a full URL rather than a path; the origin is public knowledge. */
function absolute(path: string): string {
  return `${window.location.origin}${path}`;
}
