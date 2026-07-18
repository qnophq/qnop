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

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { AuditEventListResponse } from '../generated';
import { auditApi } from '../config';

/** Query parameters for the organisation-wide audit list (issue #466). */
export interface AuditLogParams {
  eventType?: string;
  actorId?: string;
  /** Restrict to system-generated events (no human actor); precedes actorId. */
  actorSystem?: boolean;
  documentId?: string;
  /** ISO-8601 lower/upper bounds on the event time (inclusive). */
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export const auditKeys = {
  all: ['audit'] as const,
  list: (params: AuditLogParams) => [...auditKeys.all, 'list', params] as const,
};

/**
 * A page of the org-wide audit trail for the AUDITOR/ADMIN view (issue #466).
 * Keeps the previous page visible while the next one loads, so paging and
 * filtering never flash an empty table. Empty-string filters are normalised to
 * `undefined` so they drop out of the request entirely.
 */
export function useAuditLog(params: AuditLogParams) {
  return useQuery<AuditEventListResponse>({
    queryKey: auditKeys.list(params),
    queryFn: async () => {
      const response = await auditApi.listAuditEvents({
        eventType: params.eventType || undefined,
        actorId: params.actorId || undefined,
        actorSystem: params.actorSystem || undefined,
        documentId: params.documentId || undefined,
        from: params.from || undefined,
        to: params.to || undefined,
        page: params.page,
        size: params.size,
      });
      return response.data;
    },
    placeholderData: keepPreviousData,
  });
}
