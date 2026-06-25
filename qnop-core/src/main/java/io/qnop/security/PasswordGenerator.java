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

import java.security.SecureRandom;

/**
 * Generates strong, human-transcribable passwords for the admin reset / self-service "generate"
 * affordances (issue #116). A fresh password is 16 characters from a 56-symbol readable alphabet
 * (the ambiguous glyphs {@code 0/O} and {@code 1/l/I} are removed so it can be read aloud or copied
 * from a one-time dialog) — about 93 bits of entropy, well above the 8-character minimum a user may
 * choose. {@link SecureRandom#nextInt(int)} is bias-free, so every glyph is equally likely.
 */
public final class PasswordGenerator {

  /** Readable alphabet — A–Z a–z 2–9 minus the ambiguous {@code 0 O 1 l I}. */
  static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

  /** Length of a generated password; deliberately above the {@code minLength: 8} policy. */
  static final int LENGTH = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  private PasswordGenerator() {}

  /** A fresh 16-character password drawn uniformly from the readable alphabet. */
  public static String generate() {
    StringBuilder password = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return password.toString();
  }
}
