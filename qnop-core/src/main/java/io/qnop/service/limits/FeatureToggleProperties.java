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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Capabilities this deployment may use at all (issue #674), overridable via {@code qnop.features.*}
 * or the matching {@code QNOP_FEATURES_*} environment variables.
 *
 * <p>The same argument as the quotas beside them (ADR-0057): these are what the operator granted,
 * not what the tenant chose, so they live where only deployment access reaches them. They are
 * {@code qnop.features.*} rather than more {@code qnop.limits.*} because a switch is not a ceiling
 * — but they belong in the same block of a configuration file, since an operator reads them
 * together.
 *
 * <p>Everything defaults to {@code true}: a Community deployment has every capability, and a
 * feature is only ever missing where somebody removed it.
 *
 * <p>Switching one off closes the door rather than hiding the handle. A disabled capability refuses
 * at its own endpoint, not merely in the list a client renders — a URL is something a person can
 * type.
 *
 * @param oidc single sign-on: the login buttons, the OAuth2 endpoints, and administering providers
 * @param annotationExport downloading a review's annotations in any format
 * @param customBranding operator logos; off means every slot serves the bundled default, including
 *     slots that already hold an upload — otherwise a downgrade would leave the previous logo in
 *     place
 * @param schedulerManualRun an administrator's run-now on a maintenance job (issue #676). Off
 *     leaves the schedule itself alone: the sweeps keep running on their cron, and only the manual
 *     trigger is withheld. It is its own switch because run-now is deliberately an override — it
 *     starts a job regardless of the job's own enabled flag — which is right for a self-hosted
 *     installation and is arbitrary load on demand for a hosted one.
 * @param schedulerJobSettings changing a job's own settings — its enabled switch and its dry-run
 *     mode (issue #677). One switch for both, because dry-run is a soft off for the jobs where it
 *     matters: the reaper and the review purge run but delete nothing in dry-run, so a deployment
 *     that withholds the enabled switch while leaving dry-run open has locked a door beside an open
 *     window. Off leaves the deployment's own configuration in charge: the jobs run as shipped.
 */
@ConfigurationProperties(prefix = "qnop.features")
public record FeatureToggleProperties(
    @DefaultValue("true") boolean oidc,
    @DefaultValue("true") boolean annotationExport,
    @DefaultValue("true") boolean customBranding,
    @DefaultValue("true") boolean schedulerManualRun,
    @DefaultValue("true") boolean schedulerJobSettings) {

  /**
   * Everything on — the Community default, for tests and non-Spring callers.
   *
   * <p>A factory and deliberately <em>not</em> a no-argument constructor. A record with a second
   * constructor gives Spring's constructor binding a choice, and it took the wrong one: every
   * configured value was ignored and the capabilities came back on, which is the exact opposite of
   * what a deployment switching them off is asking for. The integration test caught it; the
   * annotations below are what actually supplies the defaults.
   */
  public static FeatureToggleProperties all() {
    return new FeatureToggleProperties(true, true, true, true, true);
  }
}
