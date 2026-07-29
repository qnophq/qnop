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
package io.qnop.service.review.export;

/**
 * An inline image's bytes, resolved and ready to embed (issue #635 follow-up).
 *
 * <p>Resolved by the service, like the branding logo, so a renderer never reaches for storage. That
 * is the same guarantee as everywhere else in this package: a renderer holds nothing it could use
 * to read an attachment its caller may not see.
 *
 * @param fileName the original name, used as the fallback caption when the bytes cannot be embedded
 * @param content the image, already in a form document formats accept
 * @param contentType the media type of {@link #content}, after any conversion
 */
public record ExportImage(String fileName, byte[] content, String contentType) {

  public ExportImage {
    content = content == null ? new byte[0] : content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  public boolean hasContent() {
    return content.length > 0;
  }
}
