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

/** Backends that exist only self-hosted, so a host is not optional (TrackingProvider). */
const SELF_HOSTED = new Set(['matomo', 'umami']);

export interface TrackingStatus {
  severity: 'success' | 'warning';
  message: string;
}

/**
 * Says in one line whether this deployment is actually measuring anything (issue #666).
 *
 * <p>Added after a configuration that looked complete measured nothing: provider, host and site id
 * were filled in, every privacy switch had been considered — and the master toggle was still off, so
 * the server published no tracking at all and the browser loaded no script. Everything behaved as
 * designed and the screen said nothing about it.
 *
 * <p>The rules mirror `TrackingConfigService` exactly, which is the point: the same four conditions
 * that make the server publish a configuration are the ones stated here, so "nothing is happening"
 * always comes with the reason.
 */
export function trackingStatus(values: Record<string, string>): TrackingStatus | null {
  const provider = (values['tracking.provider'] ?? 'none').trim();
  const enabled = values['tracking.enabled'] === 'true';
  const siteId = (values['tracking.site_id'] ?? '').trim();
  const host = (values['tracking.host'] ?? '').trim();

  if (provider === 'none' && !enabled) {
    return null;
  }
  if (provider === 'none') {
    return { severity: 'warning', message: 'Measurement is on, but no backend is selected.' };
  }
  if (!enabled) {
    // The case that prompted this: everything filled in, nothing measured.
    return {
      severity: 'warning',
      message: `Nothing is being measured — “${label(provider)}” is configured, but usage tracking is switched off above.`,
    };
  }
  if (!siteId) {
    return {
      severity: 'warning',
      message: `Nothing is being measured — ${label(provider)} needs the site id it knows this workspace by.`,
    };
  }
  if (!host && SELF_HOSTED.has(provider)) {
    return {
      severity: 'warning',
      message: `Nothing is being measured — ${label(provider)} is self-hosted, so it needs the address of your server.`,
    };
  }
  return {
    severity: 'success',
    message: `Measuring into ${label(provider)}${host ? ` at ${host}` : ''}. Page addresses are reduced to route patterns before they are forwarded.`,
  };
}

function label(provider: string): string {
  const names: Record<string, string> = {
    matomo: 'Matomo',
    plausible: 'Plausible',
    umami: 'Umami',
    posthog: 'PostHog',
    pirsch: 'Pirsch',
  };
  return names[provider] ?? provider;
}
