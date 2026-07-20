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
  CalendarClock,
  FileText,
  KeyRound,
  LayoutDashboard,
  Mail,
  MailPlus,
  Palette,
  ScrollText,
  ServerCog,
  Settings,
  User,
  Users,
  UsersRound,
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
 * the Administration group is ADMIN-only except Audit, which AUDITOR sees too.
 * Enterprise features add their own entries at runtime via the UI extension
 * slots (ADR-0039) — the Community shell carries no placeholders (#513).
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: '',
    items: [
      { id: 'dashboard', label: 'Dashboard', path: '/', icon: LayoutDashboard },
      { id: 'reviews', label: 'Reviews', path: '/reviews', icon: FileText },
      { id: 'my-teams', label: 'My Teams', path: '/my-teams', icon: UsersRound },
    ],
  },
  {
    label: 'Administration',
    items: [
      {
        id: 'settings',
        label: 'Settings',
        path: '/admin/settings',
        icon: Settings,
        roles: ['ADMIN'],
      },
      {
        id: 'configuration',
        label: 'Configuration',
        path: '/admin/configuration',
        icon: ServerCog,
        roles: ['ADMIN'],
      },
      { id: 'users', label: 'Users', path: '/admin/users', icon: User, roles: ['ADMIN'] },
      { id: 'teams', label: 'Teams', path: '/admin/teams', icon: Users, roles: ['ADMIN'] },
      {
        id: 'branding',
        label: 'Branding',
        path: '/admin/branding',
        icon: Palette,
        roles: ['ADMIN'],
      },
      {
        id: 'oidc-providers',
        label: 'OIDC providers',
        path: '/admin/oidc-providers',
        icon: KeyRound,
        roles: ['ADMIN'],
      },
      {
        id: 'email',
        label: 'Email / SMTP',
        path: '/admin/email',
        icon: MailPlus,
        roles: ['ADMIN'],
      },
      {
        id: 'mail-templates',
        label: 'Mail templates',
        path: '/admin/mail-templates',
        icon: Mail,
        roles: ['ADMIN'],
      },
      {
        id: 'scheduler',
        label: 'Scheduler',
        path: '/admin/scheduler',
        icon: CalendarClock,
        roles: ['ADMIN'],
      },
      {
        id: 'audit',
        label: 'Audit',
        path: '/audit',
        icon: ScrollText,
        roles: ['ADMIN', 'AUDITOR'],
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

/**
 * Resolves the breadcrumb trail for a pathname from the nav config. The trail
 * is just the section context: the (optional) group label plus the current
 * item. "Dashboard" is the root itself, never a prefix on other pages.
 */
export function crumbsFor(pathname: string): Crumb[] {
  if (pathname === '/') {
    return [{ label: 'Dashboard' }];
  }
  // A colleague's public profile (issue #454) — never show the raw id.
  if (pathname.startsWith('/users/')) {
    return [{ label: 'Profile' }];
  }
  for (const group of NAV_GROUPS) {
    // Exact match, or a detail page nested under an item (e.g. /admin/teams/:id):
    // both resolve to the item's section context (the id segment is not shown).
    const item = group.items.find(
      (i) => i.path === pathname || (i.path !== '/' && pathname.startsWith(`${i.path}/`)),
    );
    if (item) {
      const trail: Crumb[] = [];
      if (group.label) {
        trail.push({ label: group.label });
      }
      trail.push({ label: item.label, to: item.path === pathname ? undefined : item.path });
      return trail;
    }
  }
  // Unknown path: a single humanised last segment.
  const last = pathname.split('/').filter(Boolean).pop() ?? '';
  return [{ label: last.charAt(0).toUpperCase() + last.slice(1) }];
}
