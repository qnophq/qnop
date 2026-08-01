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

import { beforeEach, describe, expect, it } from 'vitest';
import type { InfoBanner } from '../../api/generated';
import {
  bannerFingerprint,
  readDismissedFingerprint,
  rememberBannerDismissal,
} from './bannerDismissal';

const banner = (message: string, extra: Partial<InfoBanner> = {}): InfoBanner =>
  ({ severity: 'warning', message, ...extra }) as InfoBanner;

describe('bannerDismissal', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('remembers the dismissed notice', () => {
    const notice = banner('Maintenance on Saturday 20:00 UTC.');

    rememberBannerDismissal(notice);

    expect(readDismissedFingerprint()).toBe(bannerFingerprint(notice));
  });

  it('brings an edited notice back', () => {
    rememberBannerDismissal(banner('Maintenance on Saturday 20:00 UTC.'));

    // The operator moved the window. That is new information, and someone who
    // dismissed the old sentence has not seen this one.
    const edited = banner('Maintenance moved to Sunday 09:00 UTC.');
    expect(readDismissedFingerprint()).not.toBe(bannerFingerprint(edited));
  });

  it('notices a changed severity or link, not just changed words', () => {
    const message = 'The extraction pipeline is degraded.';
    expect(bannerFingerprint(banner(message))).not.toBe(
      bannerFingerprint(banner(message, { severity: 'critical' })),
    );
    expect(bannerFingerprint(banner(message))).not.toBe(
      bannerFingerprint(banner(message, { linkLabel: 'Status', linkUrl: 'https://s.example' })),
    );
  });

  it('is stable for the same content', () => {
    const first = banner('Maintenance on Saturday.', {
      linkLabel: 'Status',
      linkUrl: 'https://s.example',
    });
    const second = banner('Maintenance on Saturday.', {
      linkLabel: 'Status',
      linkUrl: 'https://s.example',
    });

    expect(bannerFingerprint(first)).toBe(bannerFingerprint(second));
  });
});
