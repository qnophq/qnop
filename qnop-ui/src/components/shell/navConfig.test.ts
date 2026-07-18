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
import { crumbsFor, visibleNavGroups } from './navConfig';

function ids(role: 'ADMIN' | 'MEMBER' | 'AUDITOR' | null): string[] {
  return visibleNavGroups(role).flatMap((g) => g.items.map((i) => i.id));
}

describe('visibleNavGroups', () => {
  it('shows dashboard + reviews + My Teams for a MEMBER (My Teams is for everyone)', () => {
    expect(ids('MEMBER')).toEqual(['dashboard', 'reviews', 'my-teams']);
  });

  it('adds compliance and audit for an AUDITOR but no admin items', () => {
    const items = ids('AUDITOR');
    expect(items).toContain('compliance');
    expect(items).toContain('audit');
    expect(items).not.toContain('users');
    expect(items).not.toContain('settings');
  });

  it('shows everything for an ADMIN', () => {
    expect(ids('ADMIN')).toEqual([
      'dashboard',
      'reviews',
      'my-teams',
      'compliance',
      'audit',
      'users',
      'teams',
      'settings',
      'oidc-providers',
      'email',
      'mail-templates',
      'branding',
    ]);
  });

  it('drops the now-empty admin group for a MEMBER', () => {
    const groups = visibleNavGroups('MEMBER');
    expect(groups.some((g) => g.label === 'Administration')).toBe(false);
  });

  it('shows My Teams to every authenticated role, including AUDITOR', () => {
    expect(ids('AUDITOR')).toContain('my-teams');
  });
});

describe('crumbsFor', () => {
  it('returns a single Dashboard crumb at root', () => {
    expect(crumbsFor('/')).toEqual([{ label: 'Dashboard' }]);
  });

  it('builds group > item for an admin path (no Dashboard prefix)', () => {
    expect(crumbsFor('/admin/users')).toEqual([{ label: 'Administration' }, { label: 'Users' }]);
  });

  it('builds just the item for a top-level path', () => {
    expect(crumbsFor('/reviews')).toEqual([{ label: 'Reviews' }]);
  });

  it('resolves the Email / SMTP admin path', () => {
    expect(crumbsFor('/admin/email')).toEqual([
      { label: 'Administration' },
      { label: 'Email / SMTP' },
    ]);
  });

  it('maps a nested detail path to its section, linking back to the list', () => {
    expect(crumbsFor('/admin/teams/abc-123')).toEqual([
      { label: 'Administration' },
      { label: 'Teams', to: '/admin/teams' },
    ]);
  });

  it('resolves the My Teams surface and its detail path', () => {
    expect(crumbsFor('/my-teams')).toEqual([{ label: 'My Teams' }]);
    expect(crumbsFor('/my-teams/abc-123')).toEqual([{ label: 'My Teams', to: '/my-teams' }]);
  });
});
