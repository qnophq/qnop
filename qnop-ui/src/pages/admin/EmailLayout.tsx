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

import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { PageHeader } from '../../components/admin/layout/PageHeader';

const EMAIL_TABS = [
  { path: 'server', label: 'Server' },
  { path: 'templates', label: 'Templates' },
] as const;

/**
 * Shell for the `/admin/email/*` admin area (issue #525): one page-level
 * header plus a Server / Templates tab strip whose active tab is derived
 * from the URL segment — the tab is the route, so deep links and history
 * work for free. Children render through the outlet. The header stays on
 * every child, the template editor included — child pages heading below
 * it use h2, keeping the single page-level h1 here.
 */
export function EmailLayout() {
  const location = useLocation();

  const activeTab = location.pathname.startsWith('/admin/email/templates') ? 'templates' : 'server';

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Email"
        description="Outgoing email: the SMTP server and the transactional templates qnop sends. Changes apply without a restart."
      />
      {/* Real links, not click handlers, so middle-click / copy-link work like the rest of the nav. */}
      <Tabs
        value={activeTab}
        aria-label="Email admin sub-pages"
        sx={{ borderBottom: 1, borderColor: 'divider' }}
      >
        {EMAIL_TABS.map((tab) => (
          <Tab
            key={tab.path}
            component={RouterLink}
            to={`/admin/email/${tab.path}`}
            label={tab.label}
            value={tab.path}
          />
        ))}
      </Tabs>
      <Outlet />
    </Stack>
  );
}
