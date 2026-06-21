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

import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { passwordStrength } from '../../utils/passwordStrength';

const COLOR = [
  'transparent',
  'error.main',
  'warning.main',
  'primary.main',
  'success.main',
] as const;

/** A four-step password-strength bar shown under password fields (register/reset). */
export function PasswordStrengthMeter({ password }: { password: string }) {
  if (!password) {
    return null;
  }
  const { score, label } = passwordStrength(password);

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, mt: 1 }}>
      <Box sx={{ display: 'flex', gap: 0.5, flex: 1 }}>
        {[1, 2, 3, 4].map((step) => (
          <Box
            key={step}
            sx={{
              height: 4,
              flex: 1,
              borderRadius: 999,
              bgcolor: step <= score ? COLOR[score] : 'divider',
              transition: 'background-color .2s',
            }}
          />
        ))}
      </Box>
      <Typography sx={{ fontSize: 11.5, fontWeight: 500, color: COLOR[score], minWidth: 44 }}>
        {label}
      </Typography>
    </Box>
  );
}
