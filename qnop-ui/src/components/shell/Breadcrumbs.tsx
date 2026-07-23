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

import MuiBreadcrumbs from '@mui/material/Breadcrumbs';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ChevronRight } from 'lucide-react';
import { Link as RouterLink, useLocation } from 'react-router-dom';
import { useTeam } from '../../api/hooks/useTeams';
import { useMyTeam } from '../../api/hooks/useMyTeams';
import { TeamAvatar } from './TeamAvatar';
import { crumbsFor } from './navConfig';

/** The final breadcrumb on a team-detail page: the team's avatar + name (issue #509). */
function TeamCrumbInner({ name, avatarUrl }: { name: string; avatarUrl?: string | null }) {
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
      <TeamAvatar name={name} imageUrl={avatarUrl} size={18} />
      <Typography sx={{ fontSize: 13, color: 'text.primary', fontWeight: 500 }}>{name}</Typography>
    </Stack>
  );
}

/** Resolves the admin team (by id) so the /admin/teams/:id crumb shows its name + avatar. */
function AdminTeamCrumb({ id }: { id: string }) {
  const { data } = useTeam(id);
  return data ? <TeamCrumbInner name={data.name} avatarUrl={data.avatarUrl} /> : null;
}

/** Resolves a My-Teams team (by id or slug) so its detail crumb shows its name + avatar. */
function MyTeamCrumb({ id }: { id: string }) {
  const { data } = useMyTeam(id);
  return data ? <TeamCrumbInner name={data.name} avatarUrl={data.avatarUrl} /> : null;
}

export function Breadcrumbs() {
  const { pathname } = useLocation();
  const crumbs = crumbsFor(pathname);
  // A team-detail page appends a resolved crumb (name + avatar) after the "Teams" link (issue #509).
  const adminTeam = pathname.match(/^\/admin\/teams\/([^/]+)$/);
  const myTeam = pathname.match(/^\/my-teams\/([^/]+)$/);

  return (
    <MuiBreadcrumbs
      separator={<ChevronRight size={13} />}
      aria-label="Breadcrumb"
      sx={{ fontSize: 13, '& .MuiBreadcrumbs-separator': { mx: 0.5, color: 'text.disabled' } }}
    >
      {crumbs.map((c, i) => {
        const isLast = i === crumbs.length - 1;
        // A crumb with a `to` is always a link — even when it is the last crumb,
        // as on a detail page where the trailing "Teams" links back to the list.
        if (c.to) {
          return (
            <Link
              key={i}
              component={RouterLink}
              to={c.to}
              underline="hover"
              sx={{ fontSize: 13, color: 'text.secondary' }}
            >
              {c.label}
            </Link>
          );
        }
        return (
          <Typography
            key={i}
            sx={{
              fontSize: 13,
              color: isLast ? 'text.primary' : 'text.disabled',
              fontWeight: isLast ? 500 : 400,
            }}
          >
            {c.label}
          </Typography>
        );
      })}
      {adminTeam ? <AdminTeamCrumb key="team" id={adminTeam[1]} /> : null}
      {myTeam ? <MyTeamCrumb key="team" id={myTeam[1]} /> : null}
    </MuiBreadcrumbs>
  );
}
