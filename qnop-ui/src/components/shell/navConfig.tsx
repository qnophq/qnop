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

import {
  FileText,
  LayoutDashboard,
  Settings,
  ShieldCheck,
  User,
  Users,
  type LucideIcon,
} from 'lucide-react';
import type { UserRole } from '../../api/generated';

export interface NavItem {
  id: string;
  label: string;
  path: string;
  icon: LucideIcon;
  /** Roles allowed to see the item; omit for "any authenticated user". */
  roles?: UserRole[];
}

export interface NavGroup {
  /** Group heading; empty for the top, label-less group. */
  label: string;
  items: NavItem[];
}

/**
 * The sidebar navigation, mirroring the design prototype (`app/shell.jsx`) but
 * scoped to qnop's actual surfaces and role model. Visibility is role-aware:
 * the Administration group is ADMIN-only; Compliance is for AUDITOR (and ADMIN).
 * Destinations that have no screen yet route to the shared "coming soon"
 * placeholder so navigation and role-gating are fully exercised (#102).
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: '',
    items: [
      { id: 'dashboard', label: 'Dashboard', path: '/', icon: LayoutDashboard },
      { id: 'reviews', label: 'Reviews', path: '/reviews', icon: FileText },
    ],
  },
  {
    label: 'Verwaltung',
    items: [
      {
        id: 'compliance',
        label: 'Compliance',
        path: '/compliance',
        icon: ShieldCheck,
        roles: ['ADMIN', 'AUDITOR'],
      },
      { id: 'users', label: 'Benutzer', path: '/admin/users', icon: User, roles: ['ADMIN'] },
      { id: 'teams', label: 'Teams', path: '/admin/teams', icon: Users, roles: ['ADMIN'] },
      {
        id: 'settings',
        label: 'Einstellungen',
        path: '/admin/settings',
        icon: Settings,
        roles: ['ADMIN'],
      },
    ],
  },
];

/** Whether a nav item is visible to the given role. */
export function isNavItemVisible(item: NavItem, role: UserRole | null): boolean {
  if (!item.roles) {
    return true;
  }
  return role !== null && item.roles.includes(role);
}

/** The nav groups filtered for a role, dropping any group left with no items. */
export function visibleNavGroups(role: UserRole | null): NavGroup[] {
  return NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => isNavItemVisible(item, role)),
  })).filter((group) => group.items.length > 0);
}

/** All items flattened — used to resolve the active item / breadcrumb label. */
export const ALL_NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((g) => g.items);

export interface Crumb {
  label: string;
  to?: string;
}

/** Resolves the breadcrumb trail for a pathname from the nav config. */
export function crumbsFor(pathname: string): Crumb[] {
  if (pathname === '/') {
    return [{ label: 'Dashboard' }];
  }
  for (const group of NAV_GROUPS) {
    const item = group.items.find((i) => i.path === pathname);
    if (item) {
      const trail: Crumb[] = [{ label: 'Dashboard', to: '/' }];
      if (group.label) {
        trail.push({ label: group.label });
      }
      trail.push({ label: item.label });
      return trail;
    }
  }
  // Unknown path: Dashboard + a humanised last segment.
  const last = pathname.split('/').filter(Boolean).pop() ?? '';
  return [{ label: 'Dashboard', to: '/' }, { label: last.charAt(0).toUpperCase() + last.slice(1) }];
}
