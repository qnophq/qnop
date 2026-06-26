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

import { useState, type MouseEvent } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Popover from '@mui/material/Popover';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { SlidersHorizontal } from 'lucide-react';

interface SampleVariablesPopoverProps {
  placeholders: string[];
  /** The effective value shown per placeholder (demo value or the operator's override). */
  values: Record<string, string>;
  onChange: (name: string, value: string) => void;
}

/**
 * Lets the operator override the sample data the live preview renders with (issue #145): one field
 * per placeholder, prefilled from the effective sampleVars the server returns. Editing a field
 * re-renders the preview. Nothing to configure for a template that takes no variables.
 */
export function SampleVariablesPopover({
  placeholders,
  values,
  onChange,
}: SampleVariablesPopoverProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);

  if (placeholders.length === 0) {
    return null;
  }

  return (
    <>
      <Button
        size="small"
        variant="text"
        color="inherit"
        startIcon={<SlidersHorizontal size={15} />}
        onClick={(event: MouseEvent<HTMLElement>) => setAnchorEl(event.currentTarget)}
        sx={{ fontSize: 12.5 }}
      >
        Variables
      </Button>
      <Popover
        open={Boolean(anchorEl)}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        <Box sx={{ p: 2, width: 290 }}>
          <Typography sx={{ fontSize: 12, fontWeight: 600, color: 'text.secondary', mb: 1.5 }}>
            Sample variables
          </Typography>
          <Stack spacing={1.5}>
            {placeholders.map((name) => (
              <TextField
                key={name}
                label={name}
                value={values[name] ?? ''}
                onChange={(event) => onChange(name, event.target.value)}
                size="small"
                fullWidth
                slotProps={{ htmlInput: { sx: { fontFamily: 'monospace', fontSize: 13 } } }}
              />
            ))}
          </Stack>
        </Box>
      </Popover>
    </>
  );
}
