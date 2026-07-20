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

import { Navigate, useParams } from 'react-router-dom';

/**
 * Legacy redirect for pre-#525 bookmarks: `/admin/mail-templates/:key` →
 * `/admin/email/templates/:key`. `<Navigate>` cannot interpolate path
 * params, so this tiny component reads the (already-decoded) key and
 * re-encodes it for the new location.
 */
export function MailTemplatesKeyRedirect() {
  const { key = '' } = useParams();
  return <Navigate to={`/admin/email/templates/${encodeURIComponent(key)}`} replace />;
}
