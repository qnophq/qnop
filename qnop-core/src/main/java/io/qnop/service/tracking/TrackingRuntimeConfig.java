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
package io.qnop.service.tracking;

/**
 * Usage tracking as it is actually configured right now (issue #666) — the settings resolved into
 * the handful of facts the proxy and the client each need.
 *
 * <p>Its existence means tracking is on <em>and</em> fully configured; a half-filled form yields no
 * instance at all rather than an object nobody can use.
 *
 * @param provider which backend receives the measurements
 * @param siteId the backend's identifier for this site, passed to the browser
 * @param scriptUrl absolute URL of the vendor script the proxy fetches
 * @param collectBaseUrl absolute base the proxy appends an allowed collect path to
 * @param consentRequired whether the client must ask before loading anything
 * @param respectDnt whether a Do-Not-Track browser is left alone
 * @param trackPrivilegedRoles whether administrators and auditors are measured too
 * @param forwardClientIp how much of the visitor's address travels with each measurement (issue
 *     #712): nothing, truncated, or exact
 */
public record TrackingRuntimeConfig(
    TrackingProvider provider,
    String siteId,
    String scriptUrl,
    String collectBaseUrl,
    boolean consentRequired,
    boolean respectDnt,
    boolean trackPrivilegedRoles,
    ClientIpForwarding forwardClientIp) {}
