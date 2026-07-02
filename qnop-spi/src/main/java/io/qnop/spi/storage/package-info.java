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

/**
 * Object-storage extension point (ADR-0005). A {@link io.qnop.spi.storage.StorageProvider} stores,
 * retrieves and deletes binary content by opaque key; the Community default is an S3/MinIO adapter
 * in {@code io.qnop.service.storage}, but an add-on may supply its own. Pure contract: no Spring,
 * no persistence, no internal-module types — only the JDK.
 */
package io.qnop.spi.storage;
