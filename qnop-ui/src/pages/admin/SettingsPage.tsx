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

import { useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import Typography from '@mui/material/Typography';
import { ApplicationSettingsForm } from '../../components/admin/settings/ApplicationSettingsForm';

interface SettingsTab {
  readonly id: string;
  readonly label: string;
  readonly content: ReactNode;
}

/** Placeholder for the settings sections that land in the follow-up PRs of #106. */
function ComingSoon({ what }: { what: string }) {
  return (
    <Typography color="text.secondary" sx={{ fontSize: 14 }}>
      {what} arrives in a follow-up of #106.
    </Typography>
  );
}

const TABS: readonly SettingsTab[] = [
  { id: 'application', label: 'Application', content: <ApplicationSettingsForm /> },
  {
    id: 'oidc',
    label: 'OIDC providers',
    content: <ComingSoon what="Single sign-on provider management" />,
  },
  { id: 'mail', label: 'Mail templates', content: <ComingSoon what="The mail template editor" /> },
  { id: 'branding', label: 'Branding', content: <ComingSoon what="Logo and branding uploads" /> },
];

/**
 * Admin settings screen (issue #106): a tabbed shell over the application
 * settings, OIDC providers, mail templates and branding. This change wires the
 * shell and the Application tab; the remaining tabs follow in separate PRs.
 */
export function SettingsPage() {
  const [active, setActive] = useState(0);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1" sx={{ fontSize: 28 }}>
          Settings
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Workspace, security policy, single sign-on, branding and mail.
        </Typography>
      </Box>

      <Box sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs
          value={active}
          onChange={(_, next) => setActive(next)}
          variant="scrollable"
          scrollButtons="auto"
          aria-label="Settings sections"
        >
          {TABS.map((tab) => (
            <Tab key={tab.id} label={tab.label} id={`settings-tab-${tab.id}`} />
          ))}
        </Tabs>
      </Box>

      {TABS.map((tab, index) => (
        <Box
          key={tab.id}
          role="tabpanel"
          hidden={active !== index}
          aria-labelledby={`settings-tab-${tab.id}`}
        >
          {active === index && tab.content}
        </Box>
      ))}
    </Stack>
  );
}
