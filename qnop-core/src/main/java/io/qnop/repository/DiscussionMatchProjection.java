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
package io.qnop.repository;

import io.qnop.entity.AnnotationStatus;
import java.util.UUID;

/**
 * A discussion text matched by the global search (issue #540): the comment, its thread, and the
 * review it lives in — enough for a deep-linking hit with a status cue, no author.
 */
public record DiscussionMatchProjection(
    UUID commentId,
    UUID annotationId,
    UUID documentId,
    String documentSlug,
    String documentTitle,
    AnnotationStatus annotationStatus,
    String body) {}
