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

import type { AnnotationView } from '../../api/generated';

/**
 * A document-scoped annotation (issue #395): a general remark that pins to no
 * passage, so it carries no anchor and no placement on any version — valid
 * everywhere, never orphaned (ADR-0009 amendment). The signal is unambiguous
 * because an *orphaned* annotation keeps its anchor (a placement whose status is
 * ORPHANED), so a missing anchor uniquely marks the document-scoped case.
 */
export function isDocumentScoped(annotation: Pick<AnnotationView, 'anchor'>): boolean {
  return !annotation.anchor;
}
