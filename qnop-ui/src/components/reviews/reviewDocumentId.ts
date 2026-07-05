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

import { createContext, useContext } from 'react';
import { useParams } from 'react-router-dom';

/** Provided by {@code ReviewParamGate} once a slug URL resolved (issue #411). */
export const ReviewDocumentIdContext = createContext<string | null>(null);

/**
 * The canonical document id under a /reviews/:documentId route (issue #411).
 * Inside a {@code ReviewParamGate} this is the resolved id even when the URL
 * carries a slug; without a gate (unit tests render pages directly) it falls
 * back to the raw route segment.
 */
export function useReviewDocumentId(): string {
  const resolved = useContext(ReviewDocumentIdContext);
  const { documentId = '' } = useParams();
  return resolved ?? documentId;
}
