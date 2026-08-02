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
package io.qnop.service.limits;

/**
 * This deployment does not offer the capability that was asked for (issue #674).
 *
 * <p>Distinct from a full quota (409, "no room right now") and from a permission denial (403 for
 * <em>this</em> caller): the answer here is the same for everybody and will not change until
 * somebody with deployment access changes it. Saying which capability, by name, is what lets an
 * administrator take the question to the right person instead of hunting for a setting that is not
 * there.
 */
public class FeatureDisabledException extends RuntimeException {

  private final Feature feature;

  public FeatureDisabledException(Feature feature) {
    super(feature.describe());
    this.feature = feature;
  }

  public String code() {
    return feature.code();
  }

  public Feature feature() {
    return feature;
  }

  /** The capabilities a deployment can withhold. */
  public enum Feature {
    OIDC("OIDC_DISABLED", "single sign-on"),
    ANNOTATION_EXPORT("ANNOTATION_EXPORT_DISABLED", "annotation export"),
    CUSTOM_BRANDING("CUSTOM_BRANDING_DISABLED", "custom branding"),
    SCHEDULER_MANUAL_RUN("SCHEDULER_MANUAL_RUN_DISABLED", "starting a maintenance job by hand"),
    SCHEDULER_JOB_SETTINGS(
        "SCHEDULER_JOB_SETTINGS_DISABLED",
        "changing a maintenance job's enabled or dry-run setting");

    private final String code;
    private final String subject;

    Feature(String code, String subject) {
      this.code = code;
      this.subject = subject;
    }

    public String code() {
      return code;
    }

    String describe() {
      return subject + " is not available on this deployment";
    }
  }
}
