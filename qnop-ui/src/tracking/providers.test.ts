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

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ADAPTERS,
  COLLECT_BASE,
  createTracker,
  sendBeacon,
  type Beacon,
  type BeaconContext,
} from './providers';

const ctx: BeaconContext = {
  siteId: 'site-1',
  origin: 'https://qnop.example',
  hostname: 'qnop.example',
  language: 'en-GB',
  screenWidth: 1440,
  screenHeight: 900,
  visitId: 'visit-1',
};

const PATTERN = '/reviews/:documentId/tasks';

function queryOf(beacon: Beacon): URLSearchParams {
  return new URLSearchParams(beacon.path.slice(beacon.path.indexOf('?') + 1));
}

function bodyOf(beacon: Beacon): Record<string, unknown> {
  return JSON.parse(beacon.body ?? '{}') as Record<string, unknown>;
}

/**
 * The golden beacons: exactly what this client sends, one page view and one event per backend.
 *
 * The same file is read by the server's TrackingMeasurementValidatorTest, which asserts that every
 * entry is accepted and that the paths per backend equal TrackingProvider's allowlist — so the
 * client and the server cannot drift apart without one of the two test suites failing. Regenerate
 * with `vitest run -u` after a deliberate change to a request shape, and review the diff.
 */
const FIXTURE = './__fixtures__/beacons.json';
const FIXED_NOW = 1_700_000_000_000;

function goldenBeacons(): Array<Beacon & { provider: string; kind: string }> {
  return Object.entries(ADAPTERS).flatMap(([provider, adapter]) => [
    { provider, kind: 'pageview', ...adapter.pageview(ctx, PATTERN) },
    { provider, kind: 'event', ...adapter.event(ctx, 'review_created', PATTERN) },
  ]);
}

describe('tracker adapters (issue #910)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('matches the golden beacons the server validates against', async () => {
    vi.useFakeTimers({ now: FIXED_NOW });
    await expect(`${JSON.stringify(goldenBeacons(), null, 2)}\n`).toMatchFileSnapshot(FIXTURE);
  });

  it('never sends a title, a referrer or anything from the real location', () => {
    for (const beacon of goldenBeacons()) {
      const serialized = `${beacon.path} ${beacon.body ?? ''}`;
      // The route pattern travels; nothing from the real location does.
      expect(decodeURIComponent(serialized)).toContain(PATTERN);
      expect(serialized).not.toMatch(/action_name|"\$title"|"title":"[^"]/);
      expect(serialized).not.toMatch(/"referrer":"[^"]|ref=[^&]/);
    }
  });

  it('builds Matomo tracking-API requests without cookies or a title', () => {
    const pageview = ADAPTERS.matomo.pageview(ctx, PATTERN);
    expect(pageview.method).toBe('GET');
    const q = queryOf(pageview);
    expect(q.get('idsite')).toBe('site-1');
    expect(q.get('rec')).toBe('1');
    expect(q.get('url')).toBe(`https://qnop.example${PATTERN}`);
    expect(q.get('e_a')).toBeNull();

    const event = queryOf(ADAPTERS.matomo.event(ctx, 'export_generated', PATTERN));
    expect(event.get('e_c')).toBe('qnop');
    expect(event.get('e_a')).toBe('export_generated');
  });

  it('builds Plausible events in the shape its own script sends', () => {
    const beacon = ADAPTERS.plausible.pageview(ctx, PATTERN);
    expect(beacon.method).toBe('POST');
    expect(bodyOf(beacon)).toEqual({
      name: 'pageview',
      url: `https://qnop.example${PATTERN}`,
      domain: 'site-1',
      referrer: null,
    });
    expect(bodyOf(ADAPTERS.plausible.event(ctx, 'annotation_created', PATTERN)).name).toBe(
      'annotation_created',
    );
  });

  it('builds Umami events with a name only for custom events', () => {
    const pageview = bodyOf(ADAPTERS.umami.pageview(ctx, PATTERN));
    expect(pageview.type).toBe('event');
    expect(pageview.payload).toMatchObject({
      website: 'site-1',
      hostname: 'qnop.example',
      url: PATTERN,
      screen: '1440x900',
    });
    expect(pageview.payload).not.toHaveProperty('name');
    const event = bodyOf(ADAPTERS.umami.event(ctx, 'review_finalized', PATTERN));
    expect(event.payload).toMatchObject({ name: 'review_finalized' });
  });

  it('builds anonymous PostHog captures', () => {
    const beacon = bodyOf(ADAPTERS.posthog.pageview(ctx, PATTERN));
    expect(beacon).toMatchObject({
      api_key: 'site-1',
      event: '$pageview',
      distinct_id: 'visit-1',
      properties: {
        $current_url: `https://qnop.example${PATTERN}`,
        $process_person_profile: false,
      },
    });
  });

  it('builds Pirsch hits and events with the route pattern', () => {
    const hit = ADAPTERS.pirsch.pageview(ctx, PATTERN);
    expect(hit.method).toBe('GET');
    expect(queryOf(hit).get('code')).toBe('site-1');
    expect(queryOf(hit).get('url')).toBe(`https://qnop.example${PATTERN}`);
    const event = ADAPTERS.pirsch.event(ctx, 'review_created', PATTERN);
    expect(event.method).toBe('POST');
    expect(bodyOf(event)).toMatchObject({
      identification_code: 'site-1',
      event_name: 'review_created',
      url: `https://qnop.example${PATTERN}`,
    });
  });
});

describe('createTracker', () => {
  it('returns nothing for a provider it does not know, including inherited names', () => {
    expect(createTracker('hotjar', 'x', vi.fn())).toBeNull();
    expect(createTracker('toString', 'x', vi.fn())).toBeNull();
  });

  it('reports events against the route pattern of the last page view', () => {
    const send = vi.fn();
    const tracker = createTracker('plausible', 'site-1', send);
    tracker?.pageview(PATTERN);
    tracker?.event('annotation_created');
    expect(send).toHaveBeenCalledTimes(2);
    const event = send.mock.calls[1][0] as Beacon;
    expect(bodyOf(event)).toMatchObject({
      name: 'annotation_created',
      url: `${window.location.origin}${PATTERN}`,
    });
  });
});

describe('sendBeacon', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts to this origin without credentials or a referrer, and swallows failures', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('offline'));
    vi.stubGlobal('fetch', fetchMock);

    sendBeacon({ method: 'POST', path: '/api/send', body: '{}', contentType: 'application/json' });
    await Promise.resolve();

    expect(fetchMock).toHaveBeenCalledWith(
      `${COLLECT_BASE}/api/send`,
      expect.objectContaining({
        method: 'POST',
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
        keepalive: true,
      }),
    );
  });
});
