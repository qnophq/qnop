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
package io.qnop.spi.auth;

import java.util.Optional;

/**
 * Authenticates one bearer credential that is not a qnop user token (issue #686, ADR-0060).
 *
 * <p>Consulted only after the core's own token validation has rejected the credential, at a fixed
 * point inside bearer processing — after the rate limiters and CSRF handling, before authorization.
 * An implementation therefore cannot influence filter ordering, weaken the human login path, or
 * change how failures render (an unclaimed credential stays the core's standard 401).
 *
 * <p>Return {@linkplain Optional#empty() empty} for a credential this authenticator does not
 * recognise — never throw for that case. All registered authenticators are consulted in bean order;
 * the first claim wins. Credential lifecycle — issuance, storage, expiry, revocation — is entirely
 * the implementation's business: the core's revocation store is user-keyed and does not apply.
 *
 * <p>Implementations must be safe to call from any thread and should be constant-time in their
 * credential comparison.
 */
@FunctionalInterface
public interface BearerCredentialAuthenticator {

  /** The authenticated machine principal, or empty when this credential is not this add-on's. */
  Optional<MachinePrincipal> authenticate(String bearerCredential);
}
