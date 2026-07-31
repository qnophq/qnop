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
package io.qnop.service.convert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Puts the concurrency limit (issue #651) in front of the converter, for everyone.
 *
 * <p>Wired here rather than annotated onto {@link ThrottledOfficeConverter} so the decorator stays
 * a plain class a unit test can build around a fake, and so the delegate it wraps is named
 * explicitly instead of resolved out of a bean type it also implements.
 *
 * <p>{@link Primary} because every caller injects the {@link OfficeConverter} interface and none of
 * them should have to know a limit exists: the export path (issue #639) and the DOCX ingest (issue
 * #343) both get it by asking for what they already asked for.
 */
@Configuration
public class OfficeConverterConfiguration {

  /**
   * Declared as the concrete type on purpose: callers get it as an {@link OfficeConverter}, and the
   * gauges (ADR-0037) can still ask it how full it is without a cast.
   */
  @Bean
  @Primary
  ThrottledOfficeConverter throttledOfficeConverter(
      LibreOfficeConverter delegate, OfficeConverterProperties properties) {
    return new ThrottledOfficeConverter(delegate, properties);
  }
}
