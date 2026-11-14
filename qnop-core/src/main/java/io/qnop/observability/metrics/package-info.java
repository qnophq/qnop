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
 * The meters qnop records about its own work (issue #962, ADR-0065): one small class per area, each
 * owning its meter names and its tag values.
 *
 * <p>Two rules hold for everything in here. A tag value comes from a closed set, an enum or a fixed
 * catalogue, and never from user data: no ids, names, addresses or file names. And latency is
 * published as histogram buckets with fixed boundaries, so it can be aggregated across instances.
 */
package io.qnop.observability.metrics;
