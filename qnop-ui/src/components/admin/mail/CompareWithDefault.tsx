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

import { useState } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Collapse from '@mui/material/Collapse';
import Typography from '@mui/material/Typography';
import { ChevronDown, ChevronRight } from 'lucide-react';

interface CompareWithDefaultProps {
  /** What the panel reveals, e.g. "default subject". */
  label: string;
  /** The built-in default content to show. */
  value: string;
}

/**
 * A collapsible panel revealing a field's built-in default (issue #144), so an admin can see what
 * they're overriding before saving or resetting. Always monospaced and scrollable for long bodies.
 */
export function CompareWithDefault({ label, value }: CompareWithDefaultProps) {
  const [open, setOpen] = useState(false);
  return (
    <Box sx={{ mt: 0.75 }}>
      <ButtonBase
        onClick={() => setOpen((o) => !o)}
        focusRipple
        aria-expanded={open}
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.5,
          borderRadius: 1,
          px: 0.5,
          py: 0.25,
          color: 'text.secondary',
          '&:hover': { color: 'text.primary' },
        }}
      >
        {open ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
        <Typography sx={{ fontSize: 13 }}>Compare with {label}</Typography>
      </ButtonBase>
      <Collapse in={open}>
        <Box
          component="pre"
          sx={{
            m: 0,
            mt: 0.5,
            p: 1.5,
            fontSize: 12.5,
            fontFamily: 'monospace',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            color: 'text.secondary',
            border: '1px dashed',
            borderColor: 'divider',
            borderRadius: 1,
            maxHeight: 320,
            overflow: 'auto',
          }}
        >
          {value}
        </Box>
      </Collapse>
    </Box>
  );
}
