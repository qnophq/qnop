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

import Tooltip from '@mui/material/Tooltip';
import Box from '@mui/material/Box';
import { EyeOff } from 'lucide-react';
import { ToneBadge } from '../admin/ToneBadge';

/**
 * Flags an anonymous review (issue #422) so every participant knows names are hidden. A neutral
 * pill with an eye-off glyph, shown next to the workflow badge in the header and on the overview
 * card.
 */
export function AnonymousBadge({ compact = false }: { compact?: boolean }) {
  return (
    <Tooltip title="Anonymous review — reviewer names are hidden">
      <Box component="span" sx={{ display: 'inline-flex' }} data-testid="anonymous-badge">
        <ToneBadge
          tone="neutral"
          label={compact ? 'Anon' : 'Anonymous'}
          icon={<EyeOff size={12} aria-hidden />}
        />
      </Box>
    </Tooltip>
  );
}
