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
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { ToneBadge } from '../admin/ToneBadge';
import { auditEventMeta } from '../../utils/auditEvents';

/**
 * An audit event type rendered as a semantic, colour-coded pill with its
 * plain-language name (issue #466). Hovering reveals a one-line explanation plus
 * the underlying machine type, so an auditor never has to decode `annotation.
 * created` on their own — the same badge is reused in the table and the legend.
 */
export function AuditEventBadge({ eventType }: { eventType: string }) {
  const meta = auditEventMeta(eventType);
  return (
    <Tooltip
      arrow
      title={
        <Box sx={{ py: 0.25 }}>
          <Typography sx={{ fontSize: 12.5, lineHeight: 1.4 }}>{meta.description}</Typography>
          <Typography
            sx={{ fontSize: 11, mt: 0.5, opacity: 0.75, fontFamily: 'ui-monospace, monospace' }}
          >
            {eventType}
          </Typography>
        </Box>
      }
    >
      <Box component="span" sx={{ display: 'inline-flex', cursor: 'help' }}>
        <ToneBadge tone={meta.tone} label={meta.label} />
      </Box>
    </Tooltip>
  );
}
