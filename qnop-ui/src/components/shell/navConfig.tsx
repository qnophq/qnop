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
  HardDrive,
  KeyRound,
  LayoutDashboard,
  Mail,
  Palette,
  ScrollText,
  ServerCog,
  Settings,
  User,
  Users,
  UsersRound,
  type LucideIcon,
  Inbox,
} from 'lucide-react';
import i18n, { type ParseKeys, type TFunction } from 'i18next';
import type { ServerConfigFeatures, UserRole } from '../../api/generated';

/** A key in the `shell` catalog (issue #464): config holds keys, labels resolve at render. */
export type ShellKey = ParseKeys<'shell'>;

/** Translates a `shell` key; components pass their `useTranslation('shell')` t. */
export type ShellT = TFunction<'shell'>;

/** The translator for the current language, for callers outside React (and tests). */
function currentShellT(): ShellT {
  return i18n.getFixedT(null, 'shell');
}

interface NavItemConfig {
  id: string;
  labelKey: ShellKey;
  path: string;
  icon: LucideIcon;
  /** Roles allowed to see the item; omit for "any authenticated user". */
  roles?: UserRole[];
  /**
   * The deployment capability this item administers; omit for items that always exist.
   *
   * <p>A withheld capability (issue #674) refuses at its endpoints regardless — this only keeps the
   * sidebar from offering a page whose every action would be denied.
   *
   * <p>A list means "any of these": the Email item covers two sub-pages with a capability each
   * (issues #678/#679), and it is worth offering while either one remains.
   */
  feature?: keyof ServerConfigFeatures | (keyof ServerConfigFeatures)[];
}

interface NavGroupConfig {
  /** Group heading key; omitted for the top, label-less group. */
  labelKey?: ShellKey;
  items: NavItemConfig[];
}

/** A nav item with its label resolved in the active language. */
export interface NavItem extends Omit<NavItemConfig, 'labelKey'> {
  label: string;
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
const NAV_GROUPS: NavGroupConfig[] = [
  {
    items: [
      { id: 'dashboard', labelKey: 'nav.items.dashboard', path: '/', icon: LayoutDashboard },
      { id: 'reviews', labelKey: 'nav.items.reviews', path: '/reviews', icon: FileText },
      { id: 'my-teams', labelKey: 'nav.items.myTeams', path: '/my-teams', icon: UsersRound },
      // No roles: the inbox belongs to every authenticated user (issue #538).
      { id: 'messages', labelKey: 'nav.items.messages', path: '/messages', icon: Inbox },
    ],
  },
  {
    labelKey: 'nav.groups.administration',
    items: [
      {
        id: 'settings',
        labelKey: 'nav.items.settings',
        path: '/admin/settings',
        icon: Settings,
        roles: ['ADMIN'],
      },
      {
        id: 'configuration',
        labelKey: 'nav.items.configuration',
        path: '/admin/configuration',
        feature: 'deploymentConfiguration',
        icon: ServerCog,
        roles: ['ADMIN'],
      },
      {
        id: 'users',
        labelKey: 'nav.items.users',
        path: '/admin/users',
        icon: User,
        roles: ['ADMIN'],
      },
      {
        id: 'teams',
        labelKey: 'nav.items.teams',
        path: '/admin/teams',
        icon: Users,
        roles: ['ADMIN'],
      },
      {
        id: 'branding',
        labelKey: 'nav.items.branding',
        path: '/admin/branding',
        feature: 'customBranding',
        icon: Palette,
        roles: ['ADMIN'],
      },
      {
        id: 'oidc-providers',
        labelKey: 'nav.items.oidcProviders',
        path: '/admin/oidc-providers',
        feature: 'oidc',
        icon: KeyRound,
        roles: ['ADMIN'],
      },
      {
        id: 'email',
        labelKey: 'nav.items.email',
        path: '/admin/email',
        feature: ['smtpConfiguration', 'emailTemplates'],
        icon: Mail,
        roles: ['ADMIN'],
      },
      {
        id: 'scheduler',
        labelKey: 'nav.items.scheduler',
        path: '/admin/scheduler',
        icon: CalendarClock,
        roles: ['ADMIN'],
      },
      {
        id: 'storage-consistency',
        labelKey: 'nav.items.storageConsistency',
        path: '/admin/storage-consistency',
        icon: HardDrive,
        roles: ['ADMIN'],
      },
      {
        id: 'audit',
        labelKey: 'nav.items.audit',
        path: '/audit',
        icon: ScrollText,
        roles: ['ADMIN', 'AUDITOR'],
      },
    ],
  },
];

/** Whether a nav item is visible to the given role. */
export function isNavItemVisible(item: Pick<NavItem, 'roles'>, role: UserRole | null): boolean {
  if (!item.roles) {
    return true;
  }
  return role !== null && item.roles.includes(role);
}

function resolveItem({ labelKey, ...item }: NavItemConfig, t: ShellT): NavItem {
  return { ...item, label: t(labelKey) };
}

/**
 * The nav groups filtered for a role, dropping any group left with no items, with labels in the
 * language of {@code t} (the caller's `useTranslation('shell')`, so a language switch re-renders).
 */
export function visibleNavGroups(
  role: UserRole | null,
  features?: ServerConfigFeatures,
  t: ShellT = currentShellT(),
): NavGroup[] {
  return NAV_GROUPS.map((group) => ({
    label: group.labelKey ? t(group.labelKey) : '',
    items: group.items
      .filter((item) => isNavItemVisible(item, role) && isFeatureAvailable(item, features))
      .map((item) => resolveItem(item, t)),
  })).filter((group) => group.items.length > 0);
}

/**
 * Whether the capability an item administers is present.
 *
 * <p>Absent config means "not known yet", and the item stays — a sidebar that flickers items in as
 * the config arrives is worse than one that occasionally shows a page the server will refuse.
 */
function isFeatureAvailable(item: NavItemConfig, features?: ServerConfigFeatures): boolean {
  if (!item.feature || !features) {
    return true;
  }
  const required = Array.isArray(item.feature) ? item.feature : [item.feature];
  return required.some((name) => features[name]);
}

export interface Crumb {
  label: string;
  to?: string;
}

/**
 * Resolves the breadcrumb trail for a pathname from the nav config. The trail
 * is just the section context: the (optional) group label plus the current
 * item. "Dashboard" is the root itself, never a prefix on other pages.
 */
export function crumbsFor(pathname: string, t: ShellT = currentShellT()): Crumb[] {
  if (pathname === '/') {
    return [{ label: t('nav.items.dashboard') }];
  }
  // A colleague's public profile (issue #454) — never show the raw id.
  if (pathname.startsWith('/users/')) {
    return [{ label: t('breadcrumbs.profile') }];
  }
  for (const group of NAV_GROUPS) {
    // Exact match, or a detail page nested under an item (e.g. /admin/teams/:id):
    // both resolve to the item's section context (the id segment is not shown).
    const item = group.items.find(
      (i) => i.path === pathname || (i.path !== '/' && pathname.startsWith(`${i.path}/`)),
    );
    if (item) {
      const trail: Crumb[] = [];
      if (group.labelKey) {
        trail.push({ label: t(group.labelKey) });
      }
      trail.push({ label: t(item.labelKey), to: item.path === pathname ? undefined : item.path });
      return trail;
    }
  }
  // Unknown path: a single humanised last segment (a URL segment, not translatable text).
  const last = pathname.split('/').filter(Boolean).pop() ?? '';
  return [{ label: last.charAt(0).toUpperCase() + last.slice(1) }];
}
