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
package io.qnop.service.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name resolution for the guarded outbound clients (issue #904). A seam so tests can stand in a
 * resolver that answers differently on each call — the shape of a DNS-rebinding attempt.
 */
@FunctionalInterface
public interface HostResolver {

  /** The platform resolver ({@link InetAddress#getAllByName}). */
  HostResolver SYSTEM = InetAddress::getAllByName;

  /** All addresses {@code host} resolves to; never empty. */
  InetAddress[] resolve(String host) throws UnknownHostException;
}
