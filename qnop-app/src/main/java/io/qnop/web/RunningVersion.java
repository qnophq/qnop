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
package io.qnop.web;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * The version this instance runs, from Boot's build-info.properties (stamped by {@code springBoot {
 * buildInfo() }}, issue #495) with the jar manifest as fallback. It is {@value #UNKNOWN} only when
 * running from exploded classes without build info, such as an IDE run.
 */
@Component
public class RunningVersion {

  static final String UNKNOWN = "unknown";

  private final String value;

  public RunningVersion(ObjectProvider<BuildProperties> buildProperties) {
    this.value = resolve(buildProperties.getIfAvailable());
  }

  public String value() {
    return value;
  }

  private static String resolve(BuildProperties buildProperties) {
    if (buildProperties != null && buildProperties.getVersion() != null) {
      return buildProperties.getVersion();
    }
    return Optional.ofNullable(RunningVersion.class.getPackage().getImplementationVersion())
        .orElse(UNKNOWN);
  }
}
