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

import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import { Check, X } from 'lucide-react';
import { AnnotationDecision } from '../../../api/generated';

/** Accept/Reject for an open annotation, shown to the owner or the author. */
export function DecisionBar({
  disabled,
  onDecide,
}: {
  disabled: boolean;
  onDecide: (decision: AnnotationDecision) => void;
}) {
  return (
    <Stack
      direction="row"
      spacing={1}
      data-testid="decision-bar"
      sx={{ alignItems: 'center', pl: 2, py: 1 }}
    >
      <Button
        size="small"
        variant="contained"
        color="success"
        startIcon={<Check size={14} />}
        disabled={disabled}
        onClick={() => onDecide(AnnotationDecision.Accepted)}
      >
        Accept
      </Button>
      <Button
        size="small"
        variant="outlined"
        color="inherit"
        startIcon={<X size={14} />}
        disabled={disabled}
        onClick={() => onDecide(AnnotationDecision.Rejected)}
      >
        Reject
      </Button>
    </Stack>
  );
}
