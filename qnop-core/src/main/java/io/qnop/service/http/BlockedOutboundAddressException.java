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

import java.net.UnknownHostException;

/**
 * A guarded outbound connection was refused because the destination resolved to an address {@link
 * OutboundAddressPolicy} blocks (issue #904).
 *
 * <p>An {@link UnknownHostException} because that is what a DNS hook may throw: to every caller the
 * host simply has no address it may connect to, and the existing I/O error handling applies.
 */
public class BlockedOutboundAddressException extends UnknownHostException {

  private static final long serialVersionUID = 1L;

  public BlockedOutboundAddressException(String host) {
    super(host + " resolves to a blocked (private/loopback/metadata) address");
  }
}
