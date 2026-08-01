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
import type { ServerConfigTracking } from '../api/generated';
import { resolveGate } from './trackingGate';

const CONFIGURED: ServerConfigTracking = {
  provider: 'plausible',
  siteId: 'qnop.example',
  consentRequired: true,
  respectDnt: true,
  trackPrivilegedRoles: false,
};

const BASE = {
  tracking: CONFIGURED,
  consent: 'granted' as const,
  optOut: false,
  isAuthenticated: true,
  role: 'MEMBER' as string | null,
};

describe('resolveGate', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('measures when every gate agrees', () => {
    expect(resolveGate(BASE)).toBe('measure');
  });

  it('stays off where the operator configured nothing', () => {
    expect(resolveGate({ ...BASE, tracking: undefined })).toBe('off');
  });

  it('leaves a Do-Not-Track browser alone', () => {
    vi.stubGlobal('navigator', { ...navigator, doNotTrack: '1' });
    expect(resolveGate(BASE)).toBe('off');
  });

  it('honours Global Privacy Control the same way', () => {
    vi.stubGlobal('navigator', { ...navigator, globalPrivacyControl: true });
    expect(resolveGate(BASE)).toBe('off');
  });

  it('respects the personal opt-out over a granted consent', () => {
    // The account-level choice is the strongest one: someone who opted out on
    // their laptop must not be measured because this browser once said yes.
    expect(resolveGate({ ...BASE, optOut: true })).toBe('off');
  });

  it('leaves admins and auditors out unless the operator says otherwise', () => {
    expect(resolveGate({ ...BASE, role: 'ADMIN' })).toBe('off');
    expect(resolveGate({ ...BASE, role: 'AUDITOR' })).toBe('off');
    expect(
      resolveGate({
        ...BASE,
        role: 'ADMIN',
        tracking: { ...CONFIGURED, trackPrivilegedRoles: true },
      }),
    ).toBe('measure');
  });

  it('asks before measuring, and takes no for an answer', () => {
    expect(resolveGate({ ...BASE, consent: 'unanswered' })).toBe('ask');
    expect(resolveGate({ ...BASE, consent: 'denied' })).toBe('off');
  });

  it('skips the question where the operator turned consent off', () => {
    expect(
      resolveGate({
        ...BASE,
        consent: 'unanswered',
        tracking: { ...CONFIGURED, consentRequired: false },
      }),
    ).toBe('measure');
  });

  it('does not apply account-level gates to a signed-out visitor', () => {
    // The sign-in screen is measured, and a visitor has neither an account
    // setting nor a role to check.
    expect(resolveGate({ ...BASE, isAuthenticated: false, optOut: true, role: 'ADMIN' })).toBe(
      'measure',
    );
  });
});
