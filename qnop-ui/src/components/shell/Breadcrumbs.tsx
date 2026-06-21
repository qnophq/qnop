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
import Typography from '@mui/material/Typography';
import { ChevronRight } from 'lucide-react';
import { Link as RouterLink, useLocation } from 'react-router-dom';
import { crumbsFor } from './navConfig';

export function Breadcrumbs() {
  const { pathname } = useLocation();
  const crumbs = crumbsFor(pathname);

  return (
    <MuiBreadcrumbs
      separator={<ChevronRight size={13} />}
      aria-label="Breadcrumb"
      sx={{ fontSize: 13, '& .MuiBreadcrumbs-separator': { mx: 0.5, color: 'text.disabled' } }}
    >
      {crumbs.map((c, i) => {
        const isLast = i === crumbs.length - 1;
        if (c.to && !isLast) {
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
    </MuiBreadcrumbs>
  );
}
