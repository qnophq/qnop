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

import { describe, expect, it } from 'vitest';
import { trackingStatus } from './trackingStatus';

describe('trackingStatus', () => {
  it('names the master switch when everything else is filled in', () => {
    // The case this exists for: a complete-looking configuration that measures
    // nothing, with no hint anywhere on the screen.
    const status = trackingStatus({
      'tracking.enabled': 'false',
      'tracking.provider': 'umami',
      'tracking.host': 'https://analytics.example',
      'tracking.site_id': 'abc',
    });

    expect(status?.severity).toBe('warning');
    expect(status?.message).toMatch(/switched off/i);
  });

  it('confirms a working configuration', () => {
    const status = trackingStatus({
      'tracking.enabled': 'true',
      'tracking.provider': 'umami',
      'tracking.host': 'https://analytics.example',
      'tracking.site_id': 'abc',
    });

    expect(status?.severity).toBe('success');
    expect(status?.message).toContain('Umami');
    expect(status?.message).toContain('https://analytics.example');
  });

  it('points at the missing site id', () => {
    const status = trackingStatus({
      'tracking.enabled': 'true',
      'tracking.provider': 'plausible',
      'tracking.site_id': '  ',
    });

    expect(status?.severity).toBe('warning');
    expect(status?.message).toMatch(/site id/i);
  });

  it('asks for a host only where the backend is self-hosted', () => {
    const selfHosted = trackingStatus({
      'tracking.enabled': 'true',
      'tracking.provider': 'matomo',
      'tracking.site_id': '1',
      'tracking.host': '',
    });
    expect(selfHosted?.severity).toBe('warning');
    expect(selfHosted?.message).toMatch(/self-hosted/i);

    // Plausible has a cloud, so an empty host is a complete configuration.
    const cloud = trackingStatus({
      'tracking.enabled': 'true',
      'tracking.provider': 'plausible',
      'tracking.site_id': 'qnop.example',
      'tracking.host': '',
    });
    expect(cloud?.severity).toBe('success');
  });

  it('says nothing at all when tracking was never set up', () => {
    expect(trackingStatus({ 'tracking.enabled': 'false', 'tracking.provider': 'none' })).toBeNull();
  });

  it('flags a master switch with no backend behind it', () => {
    const status = trackingStatus({ 'tracking.enabled': 'true', 'tracking.provider': 'none' });

    expect(status?.severity).toBe('warning');
    expect(status?.message).toMatch(/no backend/i);
  });
});
