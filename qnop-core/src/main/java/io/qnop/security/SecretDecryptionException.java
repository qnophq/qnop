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
package io.qnop.security;

/**
 * A stored secret could not be decrypted with any configured key (issue #927): the ciphertext was
 * written under a key that is no longer configured, or it was altered (the GCM tag did not verify).
 *
 * <p>The message deliberately carries neither the ciphertext nor any plaintext. It extends {@link
 * IllegalStateException} so layers that may not depend on {@code io.qnop.security} (the JPA
 * converter in {@code io.qnop.entity}) can still handle it — Spring's legacy {@code Encryptors}
 * also signal a failed decryption with an {@code IllegalStateException}.
 */
public class SecretDecryptionException extends IllegalStateException {

  public SecretDecryptionException(String message) {
    super(message);
  }

  public SecretDecryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
